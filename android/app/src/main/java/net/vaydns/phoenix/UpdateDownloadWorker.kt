package net.vaydns.phoenix

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

class UpdateDownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val downloadUrl = inputData.getString("downloadUrl") ?: return@withContext Result.failure()
        val fileName = inputData.getString("fileName") ?: return@withContext Result.failure()
        val versionStr = inputData.getString("versionStr") ?: ""
        val releaseType = inputData.getString("releaseType") ?: "community"

        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            var successfulResponse: okhttp3.Response? = null

            // =======================================================
            // NETWORK REQUEST FORK
            // =======================================================
            if (releaseType.lowercase() == "private") {
                val serverBasesRaw = try {
                    mobile.Mobile.getUpdateServerURLsExported().split(",").filter { it.isNotBlank() }
                } catch (e: Exception) {
                    val primary = mobile.Mobile.getPrimaryUpdateServer()
                    if (primary.isNotBlank()) listOf(primary) else emptyList()
                }

                if (serverBasesRaw.isEmpty()) {
                    showNotification("Download Failed", "No update servers available in vault.")
                    return@withContext Result.failure()
                }

                for (base in serverBasesRaw) {
                    val cleanBase = base.trim().removeSuffix("/")
                    if (cleanBase.isEmpty()) continue
                    val fullUrl = "$cleanBase/assets/$fileName"

                    try {
                        val request = Request.Builder()
                            .url(fullUrl)
                            .addHeader("X-Phoenix-Token", mobile.Mobile.getAppSecretKeyExported())
                            .build()

                        val response = client.newCall(request).execute()
                        if (response.isSuccessful && response.body != null) {
                            successfulResponse = response
                            break
                        }
                    } catch (e: Exception) {
                        Log.e("Phoenix", "Failover attempt failed for $fullUrl: ${e.message}")
                    }
                }
            } else {
                try {
                    val request = Request.Builder().url(downloadUrl).build()
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful && response.body != null) {
                        successfulResponse = response
                    }
                } catch (e: Exception) {
                    Log.e("Phoenix", "GitHub download attempt failed: ${e.message}")
                }
            }

            if (successfulResponse == null || successfulResponse.body == null) {
                showNotification("Download Failed", "Update server unreachable.")
                return@withContext Result.failure()
            }

            // =======================================================
            // DISK WRITING (SCOPED STORAGE)
            // =======================================================
            val body = successfulResponse.body!!
            val inputStream: InputStream = body.byteStream()
            val mimeType = "application/vnd.android.package-archive"

            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()

            val targetFileName = getIncrementalFileName(downloadsDir, fileName)
            var savedSuccessfully = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val contentValues = android.content.ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, targetFileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }

                    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                        savedSuccessfully = true
                    }
                } catch (e: Exception) {
                    Log.e("Phoenix", "MediaStore insert failed, falling back: ${e.message}")
                }
            }

            if (!savedSuccessfully) {
                val outputFile = File(downloadsDir, targetFileName)
                FileOutputStream(outputFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
                android.media.MediaScannerConnection.scanFile(context, arrayOf(outputFile.absolutePath), arrayOf(mimeType), null)
            }

            inputStream.close()

            // Success! Notify the user.
            showNotification(
                "Update Downloaded ($versionStr)",
                "File saved as $targetFileName in Downloads. Please uninstall the old version before installing."
            )
            Result.success()

        } catch (e: Exception) {
            e.printStackTrace()
            showNotification("Download Error", "Failed to download update: ${e.message}")
            Result.failure()
        }
    }

    private fun getIncrementalFileName(directory: File, baseFileName: String): String {
        var candidateFile = File(directory, baseFileName)
        if (!candidateFile.exists()) return baseFileName

        val nameWithoutExtension = if (baseFileName.contains(".")) baseFileName.substringBeforeLast(".") else baseFileName
        val extension = if (baseFileName.contains(".")) "." + baseFileName.substringAfterLast(".") else ""

        var counter = 1
        while (candidateFile.exists()) {
            candidateFile = File(directory, "${nameWithoutExtension}_$counter$extension")
            counter++
        }
        return candidateFile.name
    }

    private fun showNotification(title: String, message: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "phoenix_updates_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "App Updates", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download_done) // Replace with R.drawable.ic_your_app_icon
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}