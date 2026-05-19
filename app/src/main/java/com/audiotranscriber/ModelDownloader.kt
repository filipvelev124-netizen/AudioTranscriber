package com.audiotranscriber

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

object ModelDownloader {

    const val MODEL_DIR_NAME = "vosk-model"

    fun modelDir(context: Context) = File(context.filesDir, MODEL_DIR_NAME)

    fun isDownloaded(context: Context): Boolean {
        if (!modelDir(context).exists()) return false
        return Language.getDownloaded(context) == Language.getSelected(context)
    }

    // A valid Vosk model must have these subdirectories. A directory that exists
    // but lacks them means the extraction was interrupted — passing such a path to
    // Model() causes a native crash that cannot be caught by Java try-catch.
    fun isModelValid(context: Context): Boolean {
        val dir = modelDir(context)
        return dir.isDirectory &&
               File(dir, "am").isDirectory &&
               File(dir, "conf").isDirectory
    }

    suspend fun download(
        context: Context,
        language: Language = Language.getSelected(context),
        onProgress: (Int) -> Unit,   // 0–100 while downloading, -1 while extracting
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            // Always delete any existing model before downloading a new language
            try { modelDir(context).deleteRecursively() } catch (_: Throwable) {}
            Language.clearDownloaded(context)

            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.MINUTES)
                .build()

            var response = client.newCall(Request.Builder().url(language.urls[0]).build()).execute()
            if (!response.isSuccessful && language.urls.size > 1) {
                for (url in language.urls.drop(1)) {
                    response.close()
                    response = client.newCall(Request.Builder().url(url).build()).execute()
                    if (response.isSuccessful) break
                }
            }

            if (!response.isSuccessful) {
                response.close()
                withContext(Dispatchers.Main) {
                    onError("Download failed (HTTP ${response.code}). Check your internet connection.")
                }
                return@withContext
            }

            val body = response.body ?: run {
                withContext(Dispatchers.Main) { onError("Empty response body") }
                return@withContext
            }

            val contentLength = body.contentLength()
            val zipFile = File(context.cacheDir, "vosk_model.zip")

            body.byteStream().use { input ->
                zipFile.outputStream().use { output ->
                    val buffer = ByteArray(8_192)
                    var downloaded = 0L
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (contentLength > 0) {
                            val pct = (downloaded * 100 / contentLength).toInt()
                            withContext(Dispatchers.Main) { onProgress(pct) }
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) { onProgress(-1) }

            unzip(zipFile, context.filesDir)
            zipFile.delete()

            Language.setDownloaded(context, language)
            withContext(Dispatchers.Main) { onComplete() }

        } catch (e: Throwable) {
            try { File(context.cacheDir, "vosk_model.zip").delete() } catch (_: Throwable) {}
            try { modelDir(context).deleteRecursively() } catch (_: Throwable) {}
            Language.clearDownloaded(context)
            withContext(Dispatchers.Main) { onError(e.message ?: "Unknown error") }
        }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        val canonicalTarget = targetDir.canonicalPath
        val maxEntryBytes = 300L * 1024 * 1024
        var totalExtracted = 0L
        val totalLimit     = 600L * 1024 * 1024

        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile   = File(targetDir, entry.name)
                val canonical = outFile.canonicalPath

                // Zip Slip protection
                if (!canonical.startsWith(canonicalTarget + File.separator) &&
                    canonical != canonicalTarget) {
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out ->
                        val buf = ByteArray(8_192)
                        var entryBytes = 0L
                        var read: Int
                        while (zis.read(buf).also { read = it } != -1) {
                            entryBytes    += read
                            totalExtracted += read
                            if (entryBytes > maxEntryBytes || totalExtracted > totalLimit)
                                throw SecurityException("ZIP content exceeds size limit")
                            out.write(buf, 0, read)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // Rename versioned folder (e.g. vosk-model-small-en-us-0.15) to stable name
        val extracted = targetDir.listFiles { f ->
            f.isDirectory && f.name.startsWith("vosk-model")
        }?.firstOrNull()
        extracted?.renameTo(File(targetDir, MODEL_DIR_NAME))
    }
}
