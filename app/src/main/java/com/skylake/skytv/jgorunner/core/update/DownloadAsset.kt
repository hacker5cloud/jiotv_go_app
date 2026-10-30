package com.skylake.skytv.jgorunner.core.update

import android.util.Log
import com.skylake.skytv.jgorunner.utils.Tls12SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

enum class DownloadStatus {
    SUCCESS,
    FAILED
}

data class DownloadResult(
    val status: DownloadStatus,
    val message: String
)

object DownloadAsset {
    private const val TAG = "JTVGo::DownloadAsset"

    suspend fun downloadFile(
        url: String,
        outputFile: File,
        onProgress: (DownloadProgress) -> Unit
    ): DownloadResult {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "JioTVGo-KitKat/1.0")
                    .build()

                Tls12SocketFactory.downloadHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext DownloadResult(
                            DownloadStatus.FAILED,
                            "Download failed: HTTP ${response.code()}"
                        )
                    }

                    val body = response.body() ?: return@withContext DownloadResult(
                        DownloadStatus.FAILED,
                        "Empty response body"
                    )

                    val contentLength = body.contentLength()
                    val inputStream = body.byteStream()
                    outputFile.parentFile?.mkdirs()

                    FileOutputStream(outputFile).use { outputStream ->
                        val buffer = ByteArray(16 * 1024)
                        var bytesRead: Int
                        var totalBytesRead = 0L

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            if (contentLength > 0) {
                                val progress = (totalBytesRead * 100 / contentLength).toInt()
                                onProgress(DownloadProgress(outputFile.name, progress))
                            }
                        }
                        outputStream.flush()
                    }
                }

                // Make executable on Android 4.4 KitKat internal storage
                outputFile.setExecutable(true, false)
                outputFile.setReadable(true, false)
                try {
                    Runtime.getRuntime().exec(arrayOf("chmod", "755", outputFile.absolutePath)).waitFor()
                } catch (_: Exception) {
                }

                DownloadResult(DownloadStatus.SUCCESS, "Download successful")
            } catch (e: Exception) {
                Log.e(TAG, "Download error: ${e.message}", e)
                DownloadResult(DownloadStatus.FAILED, e.message ?: "Unknown download error")
            }
        }
    }
}
