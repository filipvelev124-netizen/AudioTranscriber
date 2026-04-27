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

    // Ordered list of mirrors/versions to try — first success wins
    // 0.15 confirmed working; 0.22 returns 404 on alphacephei servers
    private val MODEL_URLS = listOf(
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.22.zip"
    )
    const val MODEL_DIR_NAME = "vosk-model"

    fun modelDir(context: Context) = File(context.filesDir, MODEL_DIR_NAME)

    fun isDownloaded(context: Context) = modelDir(context).exists()

    suspend fun download(
        context: Context,
        onProgress: (Int) -> Unit,   // 0–100 while downloading, -1 while extracting
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.MINUTES)
                .build()

            // Try each mirror in order until one succeeds
            var response = client.newCall(Request.Builder().url(MODEL_URLS[0]).build()).execute()
            for (url in MODEL_URLS) {
                response.close()
                response = client.newCall(Request.Builder().url(url).build()).execute()
                if (response.isSuccessful) break
            }

            if (!response.isSuccessful) {
                withContext(Dispatchers.Main) { onError("All download sources returned HTTP ${response.code}. Check your internet connection.") }
                return@withContext
            }

            val body = response.body ?: run {
                withContext(Dispatchers.Main) { onError("Empty response body") }
                return@withContext
            }

            val contentLength = body.contentLength()
            val zipFile = File(context.cacheDir, "vosk_model.zip")

            // Stream to disk while reporting progress
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

            // Signal "extracting" phase
            withContext(Dispatchers.Main) { onProgress(-1) }

            unzip(zipFile, context.filesDir)
            zipFile.delete()

            withContext(Dispatchers.Main) { onComplete() }

        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onError(e.message ?: "Unknown error") }
        }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        val canonicalTarget = targetDir.canonicalPath
        val maxEntryBytes = 300L * 1024 * 1024   // 300 MB per file — model is ~60 MB
        var totalExtracted = 0L
        val totalLimit     = 600L * 1024 * 1024   // 600 MB total across all entries

        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile   = File(targetDir, entry.name)
                val canonical = outFile.canonicalPath

                // Zip Slip protection: resolved path must stay inside targetDir
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

        // The zip unpacks a versioned folder (e.g. vosk-model-small-en-us-0.15).
        // Rename it to the stable MODEL_DIR_NAME so the path never changes.
        val extracted = targetDir.listFiles { f ->
            f.isDirectory && f.name.startsWith("vosk-model")
        }?.firstOrNull()
        extracted?.renameTo(File(targetDir, MODEL_DIR_NAME))
    }
}
