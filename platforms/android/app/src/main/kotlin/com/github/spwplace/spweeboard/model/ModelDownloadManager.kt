package com.github.spwplace.spweeboard.model

import android.content.Context
import android.util.Log
import androidx.lifecycle.asFlow
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Available LLM models for download.
 * Using small, quantized models suitable for mobile inference.
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val filename: String,
    val url: String,
    val sizeBytes: Long,
    val description: String
) {
    companion object {
        /**
         * Convert from Rust SpwModelInfo to Kotlin ModelInfo.
         */
        fun fromRust(rust: uniffi.spweeboard_core.SpwModelInfo): ModelInfo {
            return ModelInfo(
                id = rust.id,
                name = rust.name,
                filename = rust.filename,
                url = rust.url,
                sizeBytes = rust.sizeBytes.toLong(),
                description = rust.description
            )
        }
    }
}

/**
 * Download progress state.
 */
sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val progress: Float, val bytesDownloaded: Long, val totalBytes: Long) : DownloadState()
    data object Verifying : DownloadState()
    data class Completed(val modelPath: String) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/**
 * Model status for UI.
 */
data class ModelStatus(
    val isDownloaded: Boolean,
    val modelPath: String?,
    val sizeOnDisk: Long?
)

/**
 * Manages LLM model downloads and storage.
 * Downloads models to app's internal files directory for persistence.
 *
 * Use [getInstance] to get the singleton instance.
 */
class ModelDownloadManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloadManager"

        // Work name prefix for unique work per model
        private const val WORK_NAME_PREFIX = "model_download_"

        // Available models - loaded from Rust core (single source of truth)
        val AVAILABLE_MODELS: List<ModelInfo> by lazy {
            uniffi.spweeboard_core.availableModels().map { ModelInfo.fromRust(it) }
        }

        // Default model for initial setup
        val DEFAULT_MODEL: ModelInfo by lazy {
            val defaultId = uniffi.spweeboard_core.defaultModelId()
            AVAILABLE_MODELS.find { it.id == defaultId } ?: AVAILABLE_MODELS.first()
        }

        @Volatile
        private var instance: ModelDownloadManager? = null

        fun getInstance(context: Context): ModelDownloadManager {
            return instance ?: synchronized(this) {
                instance ?: ModelDownloadManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val modelsDir = File(context.filesDir, "models")
    private val workManager = WorkManager.getInstance(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: Flow<DownloadState> = _downloadState.asStateFlow()

    // Track which model is being downloaded for state mapping
    private var activeModelId: String? = null

    init {
        modelsDir.mkdirs()
    }

    /**
     * Start a background download using WorkManager.
     * The download survives app closure and shows notification progress.
     * Use observeDownloadState() to get updates.
     */
    fun startBackgroundDownload(modelId: String) {
        val model = AVAILABLE_MODELS.find { it.id == modelId } ?: run {
            Log.e(TAG, "Unknown model: $modelId")
            _downloadState.value = DownloadState.Error("Unknown model: $modelId")
            return
        }

        activeModelId = modelId

        val inputData = workDataOf(
            ModelDownloadWorker.KEY_MODEL_ID to model.id,
            ModelDownloadWorker.KEY_MODEL_NAME to model.name,
            ModelDownloadWorker.KEY_MODEL_URL to model.url,
            ModelDownloadWorker.KEY_MODEL_FILENAME to model.filename,
            ModelDownloadWorker.KEY_MODEL_SIZE to model.sizeBytes
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val downloadRequest = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .build()

        // Use KEEP policy to not restart if already running
        workManager.enqueueUniqueWork(
            "$WORK_NAME_PREFIX$modelId",
            ExistingWorkPolicy.KEEP,
            downloadRequest
        )

        Log.i(TAG, "Enqueued background download for $modelId")
        _downloadState.value = DownloadState.Downloading(0f, 0, model.sizeBytes)
    }

    /**
     * Observe download state for a specific model via WorkManager.
     * Returns a Flow that emits DownloadState updates.
     */
    fun observeDownloadState(modelId: String): Flow<DownloadState> {
        return workManager.getWorkInfosForUniqueWorkLiveData("$WORK_NAME_PREFIX$modelId")
            .asFlow()
            .map { workInfos ->
                val workInfo = workInfos.firstOrNull()
                workInfoToDownloadState(workInfo, modelId)
            }
    }

    /**
     * Cancel a running download.
     */
    fun cancelDownload(modelId: String) {
        workManager.cancelUniqueWork("$WORK_NAME_PREFIX$modelId")
        if (activeModelId == modelId) {
            _downloadState.value = DownloadState.Idle
            activeModelId = null
        }
        Log.i(TAG, "Cancelled download for $modelId")
    }

    /**
     * Check if a download is currently running for a model.
     */
    fun isDownloadRunning(modelId: String): Boolean {
        val workInfos = workManager.getWorkInfosForUniqueWork("$WORK_NAME_PREFIX$modelId").get()
        return workInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    }

    private fun workInfoToDownloadState(workInfo: WorkInfo?, modelId: String): DownloadState {
        if (workInfo == null) return DownloadState.Idle

        val model = AVAILABLE_MODELS.find { it.id == modelId }
        val totalBytes = model?.sizeBytes ?: 0L

        return when (workInfo.state) {
            WorkInfo.State.ENQUEUED -> {
                DownloadState.Downloading(0f, 0, totalBytes)
            }
            WorkInfo.State.RUNNING -> {
                val progress = workInfo.progress
                val percent = progress.getInt(ModelDownloadWorker.KEY_PROGRESS, 0)
                val downloaded = progress.getLong(ModelDownloadWorker.KEY_BYTES_DOWNLOADED, 0)
                val total = progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, totalBytes)
                DownloadState.Downloading(percent / 100f, downloaded, total)
            }
            WorkInfo.State.SUCCEEDED -> {
                val modelPath = workInfo.outputData.getString(ModelDownloadWorker.KEY_MODEL_PATH)
                if (modelPath != null) {
                    DownloadState.Completed(modelPath)
                } else {
                    DownloadState.Error("Download succeeded but no path returned")
                }
            }
            WorkInfo.State.FAILED -> {
                val error = workInfo.outputData.getString(ModelDownloadWorker.KEY_ERROR)
                    ?: "Download failed"
                DownloadState.Error(error)
            }
            WorkInfo.State.CANCELLED -> {
                DownloadState.Idle
            }
            WorkInfo.State.BLOCKED -> {
                DownloadState.Downloading(0f, 0, totalBytes)
            }
        }
    }

    /**
     * Check if a model is already downloaded.
     */
    fun getModelStatus(modelId: String): ModelStatus {
        val model = AVAILABLE_MODELS.find { it.id == modelId } ?: return ModelStatus(false, null, null)
        val modelFile = File(modelsDir, model.filename)

        return if (modelFile.exists() && modelFile.length() > 0) {
            ModelStatus(
                isDownloaded = true,
                modelPath = modelFile.absolutePath,
                sizeOnDisk = modelFile.length()
            )
        } else {
            ModelStatus(false, null, null)
        }
    }

    /**
     * Get path to downloaded model, or null if not downloaded.
     */
    fun getModelPath(modelId: String): String? {
        val status = getModelStatus(modelId)
        return if (status.isDownloaded) status.modelPath else null
    }

    /**
     * Download a model with progress reporting and resume support.
     * The download runs in IO dispatcher and reports progress via downloadState flow.
     * If a partial download exists, it will resume from where it left off.
     */
    suspend fun downloadModel(modelId: String): Result<String> = withContext(Dispatchers.IO) {
        val model = AVAILABLE_MODELS.find { it.id == modelId }
            ?: return@withContext Result.failure(Exception("Unknown model: $modelId"))

        val modelFile = File(modelsDir, model.filename)
        val tempFile = File(modelsDir, "${model.filename}.tmp")

        // Check if already downloaded
        if (modelFile.exists() && modelFile.length() > model.sizeBytes * 0.9) {
            _downloadState.value = DownloadState.Completed(modelFile.absolutePath)
            return@withContext Result.success(modelFile.absolutePath)
        }

        try {
            // Check for existing partial download to resume
            val existingBytes = if (tempFile.exists()) tempFile.length() else 0L
            val totalBytes = model.sizeBytes

            _downloadState.value = DownloadState.Downloading(
                progress = (existingBytes.toFloat() / totalBytes).coerceIn(0f, 1f),
                bytesDownloaded = existingBytes,
                totalBytes = totalBytes
            )

            // Build request with Range header if resuming
            val requestBuilder = Request.Builder()
                .url(model.url)
                .header("User-Agent", "spweeboard/1.0")

            if (existingBytes > 0) {
                // Request bytes from where we left off
                requestBuilder.header("Range", "bytes=$existingBytes-")
            }

            val request = requestBuilder.build()

            client.newCall(request).execute().use { response ->
                // 206 Partial Content means resume worked, 200 OK means fresh download
                if (!response.isSuccessful && response.code != 206) {
                    val error = "Download failed: ${response.code} ${response.message}"
                    _downloadState.value = DownloadState.Error(error)
                    return@withContext Result.failure(Exception(error))
                }

                val body = response.body
                    ?: run {
                        _downloadState.value = DownloadState.Error("Empty response")
                        return@withContext Result.failure(Exception("Empty response"))
                    }

                // If server doesn't support Range (200 instead of 206), start fresh
                val resumeOffset = if (response.code == 206) existingBytes else 0L
                if (response.code == 200 && existingBytes > 0) {
                    // Server doesn't support resume, delete partial and start over
                    tempFile.delete()
                }

                // Append if resuming, overwrite if starting fresh
                FileOutputStream(tempFile, response.code == 206).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead: Long = resumeOffset

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead

                            val progress = (totalRead.toFloat() / totalBytes).coerceIn(0f, 1f)
                            _downloadState.value = DownloadState.Downloading(progress, totalRead, totalBytes)
                        }
                    }
                }
            }

            // Verify and rename
            _downloadState.value = DownloadState.Verifying

            if (tempFile.length() < model.sizeBytes * 0.5) {
                // Don't delete partial file - keep for resume
                val error = "Download incomplete: got ${tempFile.length()} bytes, expected ~${model.sizeBytes}"
                _downloadState.value = DownloadState.Error(error)
                return@withContext Result.failure(Exception(error))
            }

            // Move temp file to final location
            modelFile.delete()
            if (!tempFile.renameTo(modelFile)) {
                tempFile.copyTo(modelFile, overwrite = true)
                tempFile.delete()
            }

            _downloadState.value = DownloadState.Completed(modelFile.absolutePath)
            Result.success(modelFile.absolutePath)

        } catch (e: Exception) {
            // Don't delete temp file on network errors - keep for resume
            val error = "Download failed: ${e.message}"
            _downloadState.value = DownloadState.Error(error)
            Result.failure(Exception(error, e))
        }
    }

    /**
     * Delete a downloaded model and any partial download.
     */
    fun deleteModel(modelId: String): Boolean {
        val model = AVAILABLE_MODELS.find { it.id == modelId } ?: return false
        val modelFile = File(modelsDir, model.filename)
        val tempFile = File(modelsDir, "${model.filename}.tmp")
        tempFile.delete()
        return modelFile.delete()
    }

    /**
     * Check if there's a partial download that can be resumed.
     */
    fun getPartialDownloadProgress(modelId: String): Float? {
        val model = AVAILABLE_MODELS.find { it.id == modelId } ?: return null
        val tempFile = File(modelsDir, "${model.filename}.tmp")
        if (!tempFile.exists() || tempFile.length() == 0L) return null
        return (tempFile.length().toFloat() / model.sizeBytes).coerceIn(0f, 1f)
    }

    /**
     * Clear partial download for a model.
     */
    fun clearPartialDownload(modelId: String): Boolean {
        val model = AVAILABLE_MODELS.find { it.id == modelId } ?: return false
        val tempFile = File(modelsDir, "${model.filename}.tmp")
        return tempFile.delete()
    }

    /**
     * Get total size of all downloaded models.
     */
    fun getTotalModelsSize(): Long {
        return modelsDir.listFiles()
            ?.filter { !it.name.endsWith(".tmp") }
            ?.sumOf { it.length() } ?: 0
    }

    /**
     * Get size of partial downloads (temp files).
     */
    fun getPartialDownloadsSize(): Long {
        return modelsDir.listFiles()
            ?.filter { it.name.endsWith(".tmp") }
            ?.sumOf { it.length() } ?: 0
    }

    /**
     * Get available storage space on device.
     */
    fun getAvailableStorage(): Long {
        return modelsDir.usableSpace
    }

    /**
     * Check if there's enough space to download a model.
     */
    fun hasEnoughSpace(modelId: String): Boolean {
        val model = AVAILABLE_MODELS.find { it.id == modelId } ?: return false
        val tempFile = File(modelsDir, "${model.filename}.tmp")
        val existingBytes = if (tempFile.exists()) tempFile.length() else 0L
        val bytesNeeded = model.sizeBytes - existingBytes
        // Require at least 100MB extra headroom
        return getAvailableStorage() > bytesNeeded + 100_000_000
    }

    /**
     * Get storage breakdown for UI.
     */
    fun getStorageInfo(): StorageInfo {
        val modelsSize = getTotalModelsSize()
        val partialSize = getPartialDownloadsSize()
        val available = getAvailableStorage()
        return StorageInfo(
            modelsSize = modelsSize,
            partialDownloadsSize = partialSize,
            availableSpace = available
        )
    }

    /**
     * Clear all partial downloads.
     */
    fun clearAllPartialDownloads() {
        modelsDir.listFiles()
            ?.filter { it.name.endsWith(".tmp") }
            ?.forEach { it.delete() }
    }

    /**
     * Reset download state to idle.
     */
    fun resetState() {
        _downloadState.value = DownloadState.Idle
    }
}

/**
 * Storage information for UI display.
 */
data class StorageInfo(
    val modelsSize: Long,
    val partialDownloadsSize: Long,
    val availableSpace: Long
) {
    val totalUsed: Long get() = modelsSize + partialDownloadsSize
}
