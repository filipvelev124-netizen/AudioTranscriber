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

    fun modelDir(context: Context, language: Language) =
        File(context.filesDir, "vosk-model-${language.code}")

    fun isDownloaded(context: Context, language: Language) =
        modelDir(context, language).exists()

    // Renames legacy "vosk-model" dir (English-only era) to "vosk-model-en" on first run.
    fun migrateOldModel(context: Context) {
        val old = File(context.filesDir, "vosk-model")
        val new = File(context.filesDir, "vosk-model-en")
        if (old.exists() && !new.exists()) old.renameTo(new)
    }

    suspend fun download(
        context: Context,
        language: Language,
        onProgress: (Int) -> Unit,   // 0–100 while downloading, -1 while extracting
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.MINUTES)
                .build()

            val request = Request.Builder().url(language.modelUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                withContext(Dispatchers.Main) { onError("HTTP ${response.code}") }
                return@withContext
            }

            val body = response.body ?: run {
                withContext(Dispatchers.Main) { onError("Empty response body") }
                return@withContext
            }

            val contentLength = body.contentLength()
            val zipFile = File(context.cacheDir, "vosk_model_${language.code}.zip")

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

            unzip(zipFile, context.filesDir, "vosk-model-${language.code}")
            zipFile.delete()

            withContext(Dispatchers.Main) { onComplete() }

        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onError(e.message ?: "Unknown error") }
        }
    }

    private fun unzip(zipFile: File, targetDir: File, stableName: String) {
        var topLevelDirName: String? = null
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (topLevelDirName == null) {
                    topLevelDirName = entry.name.split("/").first()
                }
                val outFile = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        topLevelDirName?.let { name ->
            val extracted = File(targetDir, name)
            val stable = File(targetDir, stableName)
            if (stable.exists()) stable.deleteRecursively()
            if (extracted.exists() && extracted.name != stableName) extracted.renameTo(stable)
        }
    }
}
