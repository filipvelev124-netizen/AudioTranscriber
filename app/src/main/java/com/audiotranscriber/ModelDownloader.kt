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

    // Each language gets its own subdirectory so switching languages never
    // requires re-downloading a model that was already saved.
    fun modelDir(context: Context, language: Language = Language.getSelected(context)) =
        File(context.filesDir, "vosk-model-${language.name.lowercase()}")

    fun isDownloaded(context: Context, language: Language = Language.getSelected(context)): Boolean {
        if (language.isOnline) return true  // online languages need no local model
        return modelDir(context, language).isDirectory
    }

    // A valid Vosk model must have these subdirectories. An incomplete extraction
    // (process killed mid-unzip) leaves the dir present but missing files —
    // passing such a path to Model() causes a native crash that can't be caught.
    fun isModelValid(context: Context, language: Language = Language.getSelected(context)): Boolean {
        if (language.isOnline) return true
        val dir = modelDir(context, language)
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
        val destDir = modelDir(context, language)
        val tempDir = File(context.filesDir, "vosk-model-downloading")
        try {
            // Only wipe THIS language's directory; other languages are kept intact
            destDir.deleteRecursively()
            tempDir.deleteRecursively()

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

            // Unzip renames the extracted folder to tempDir;
            // then rename tempDir → language-specific destDir
            unzip(zipFile, context.filesDir, tempDir.name)
            zipFile.delete()
            tempDir.renameTo(destDir)

            withContext(Dispatchers.Main) { onComplete() }

        } catch (e: Throwable) {
            try { File(context.cacheDir, "vosk_model.zip").delete() } catch (_: Throwable) {}
            try { destDir.deleteRecursively() } catch (_: Throwable) {}
            try { tempDir.deleteRecursively() } catch (_: Throwable) {}
            withContext(Dispatchers.Main) { onError(e.message ?: "Unknown error") }
        }
    }

    private fun unzip(zipFile: File, targetDir: File, destName: String) {
        val canonicalTarget = targetDir.canonicalPath
        val maxEntryBytes   = 300L * 1024 * 1024
        var totalExtracted  = 0L
        val totalLimit      = 600L * 1024 * 1024

        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile   = File(targetDir, entry.name)
                val canonical = outFile.canonicalPath

                // Zip Slip protection
                if (!canonical.startsWith(canonicalTarget + File.separator) &&
                    canonical != canonicalTarget) {
                    zis.closeEntry(); entry = zis.nextEntry; continue
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
                            entryBytes     += read
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

        // The zip unpacks a versioned folder (e.g. vosk-model-small-en-us-0.15).
        // Rename it to destName so callers get a stable directory name.
        val extracted = targetDir.listFiles { f ->
            f.isDirectory && f.name.startsWith("vosk-model")
        }?.firstOrNull()
        extracted?.renameTo(File(targetDir, destName))
    }
}
