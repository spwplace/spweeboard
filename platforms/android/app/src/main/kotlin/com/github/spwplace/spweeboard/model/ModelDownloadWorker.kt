package com.github.spwplace.spweeboard.model

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for downloading LLM models in the background.
 * Supports resume, progress notifications, and survives app closure.
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "ModelDownloadWorker"

        // Input data keys
        const val KEY_MODEL_ID = "model_id"
        const val KEY_MODEL_NAME = "model_name"
        const val KEY_MODEL_URL = "model_url"
        const val KEY_MODEL_FILENAME = "model_filename"
        const val KEY_MODEL_SIZE = "model_size"

        // Output data keys
        const val KEY_MODEL_PATH = "model_path"
        const val KEY_ERROR = "error"

        // Progress data keys
        const val KEY_PROGRESS = "progress"
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_TOTAL_BYTES = "total_bytes"

        // Notification
        const val NOTIFICATION_CHANNEL_ID = "model_download"
        const val NOTIFICATION_ID = 1001
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID)
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "Missing model ID"))
        val modelName = inputData.getString(KEY_MODEL_NAME) ?: modelId
        val modelUrl = inputData.getString(KEY_MODEL_URL)
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "Missing model URL"))
        val modelFilename = inputData.getString(KEY_MODEL_FILENAME)
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "Missing model filename"))
        val modelSize = inputData.getLong(KEY_MODEL_SIZE, 0L)

        Log.i(TAG, "Starting download for $modelId: $modelUrl")

        // Create notification channel
        createNotificationChannel()

        // Set up files
        val modelsDir = File(applicationContext.filesDir, "models")
        modelsDir.mkdirs()
        val modelFile = File(modelsDir, modelFilename)
        val tempFile = File(modelsDir, "${modelFilename}.tmp")

        // Check if already downloaded
        if (modelFile.exists() && modelFile.length() > modelSize * 0.9) {
            Log.i(TAG, "Model already downloaded: ${modelFile.absolutePath}")
            return@withContext Result.success(workDataOf(KEY_MODEL_PATH to modelFile.absolutePath))
        }

        try {
            // Start foreground with notification
            setForeground(createForegroundInfo(modelName, 0, 0, modelSize))

            // Check for existing partial download
            val existingBytes = if (tempFile.exists()) tempFile.length() else 0L

            // Build request with Range header if resuming
            val requestBuilder = Request.Builder()
                .url(modelUrl)
                .header("User-Agent", "spweeboard/1.0")

            if (existingBytes > 0) {
                requestBuilder.header("Range", "bytes=$existingBytes-")
                Log.i(TAG, "Resuming download from byte $existingBytes")
            }

            val request = requestBuilder.build()

            client.newCall(request).execute().use { response ->
                // 206 Partial Content means resume worked, 200 OK means fresh download
                if (!response.isSuccessful && response.code != 206) {
                    val error = "Download failed: ${response.code} ${response.message}"
                    Log.e(TAG, error)
                    return@withContext Result.failure(workDataOf(KEY_ERROR to error))
                }

                val body = response.body
                    ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "Empty response"))

                // If server doesn't support Range (200 instead of 206), start fresh
                val resumeOffset = if (response.code == 206) existingBytes else 0L
                if (response.code == 200 && existingBytes > 0) {
                    tempFile.delete()
                    Log.i(TAG, "Server doesn't support resume, starting fresh")
                }

                val totalBytes = if (modelSize > 0) modelSize else (body.contentLength() + resumeOffset)

                // Write to temp file
                FileOutputStream(tempFile, response.code == 206).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead: Long = resumeOffset
                        var lastProgressUpdate = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            // Check for cancellation
                            if (isStopped) {
                                Log.i(TAG, "Download cancelled")
                                return@withContext Result.failure(workDataOf(KEY_ERROR to "Download cancelled"))
                            }

                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead

                            // Update progress every 100KB to avoid too many updates
                            if (totalRead - lastProgressUpdate > 100_000) {
                                lastProgressUpdate = totalRead
                                val progress = ((totalRead.toFloat() / totalBytes) * 100).toInt()

                                // Update notification
                                setForeground(createForegroundInfo(modelName, progress, totalRead, totalBytes))

                                // Report progress to observers
                                setProgress(workDataOf(
                                    KEY_PROGRESS to progress,
                                    KEY_BYTES_DOWNLOADED to totalRead,
                                    KEY_TOTAL_BYTES to totalBytes
                                ))
                            }
                        }
                    }
                }
            }

            // Verify download
            if (tempFile.length() < modelSize * 0.5) {
                val error = "Download incomplete: got ${tempFile.length()} bytes, expected ~$modelSize"
                Log.e(TAG, error)
                return@withContext Result.failure(workDataOf(KEY_ERROR to error))
            }

            // Move temp file to final location
            modelFile.delete()
            if (!tempFile.renameTo(modelFile)) {
                tempFile.copyTo(modelFile, overwrite = true)
                tempFile.delete()
            }

            Log.i(TAG, "Download complete: ${modelFile.absolutePath}")

            // Show completion notification
            showCompletionNotification(modelName)

            Result.success(workDataOf(KEY_MODEL_PATH to modelFile.absolutePath))

        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            Result.failure(workDataOf(KEY_ERROR to "Download failed: ${e.message}"))
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Model Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows progress when downloading AI models"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun createForegroundInfo(modelName: String, progress: Int, downloaded: Long, total: Long): ForegroundInfo {
        val title = "Downloading $modelName"
        val text = if (total > 0) {
            "${formatBytes(downloaded)} / ${formatBytes(total)}"
        } else {
            "Starting download..."
        }

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun showCompletionNotification(modelName: String) {
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Download Complete")
            .setContentText("$modelName is ready to use")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }
    }
}
