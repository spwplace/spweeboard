package com.github.spwplace.spweeboard.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

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
 */
class ModelDownloadManager(private val context: Context) {

    companion object {
        // Available models - using HuggingFace CDN for direct downloads
        // These are small, quantized GGUF models suitable for mobile (ungated repos only)
        val AVAILABLE_MODELS = listOf(
            ModelInfo(
                id = "qwen2.5-0.5b",
                name = "Qwen2.5 0.5B",
                filename = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
                url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
                sizeBytes = 400_000_000, // ~400MB
                description = "Small and fast, good for SPW interpretation"
            ),
            ModelInfo(
                id = "tinyllama-1.1b",
                name = "TinyLlama 1.1B",
                filename = "tinyllama-1.1b-chat-v1.0.Q4_K_S.gguf",
                url = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_S.gguf",
                sizeBytes = 644_000_000, // ~644MB
                description = "Better quality, slightly larger"
            ),
            ModelInfo(
                id = "qwen2.5-0.5b-q8",
                name = "Qwen2.5 0.5B Q8",
                filename = "qwen2.5-0.5b-instruct-q8_0.gguf",
                url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q8_0.gguf",
                sizeBytes = 530_000_000, // ~530MB
                description = "Higher precision Qwen (better quality)"
            )
        )

        // Default model for initial setup
        val DEFAULT_MODEL = AVAILABLE_MODELS.first()
    }

    private val modelsDir = File(context.filesDir, "models")
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: Flow<DownloadState> = _downloadState.asStateFlow()

    init {
        modelsDir.mkdirs()
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
     * Download a model with progress reporting.
     * The download runs in IO dispatcher and reports progress via downloadState flow.
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
            _downloadState.value = DownloadState.Downloading(0f, 0, model.sizeBytes)

            val request = Request.Builder()
                .url(model.url)
                .header("User-Agent", "spweeboard/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val error = "Download failed: ${response.code} ${response.message}"
                    _downloadState.value = DownloadState.Error(error)
                    return@withContext Result.failure(Exception(error))
                }

                val body = response.body
                    ?: run {
                        _downloadState.value = DownloadState.Error("Empty response")
                        return@withContext Result.failure(Exception("Empty response"))
                    }

                val totalBytes = body.contentLength().takeIf { it > 0 } ?: model.sizeBytes

                FileOutputStream(tempFile).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead: Long = 0

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
                tempFile.delete()
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
            tempFile.delete()
            val error = "Download failed: ${e.message}"
            _downloadState.value = DownloadState.Error(error)
            Result.failure(Exception(error, e))
        }
    }

    /**
     * Delete a downloaded model.
     */
    fun deleteModel(modelId: String): Boolean {
        val model = AVAILABLE_MODELS.find { it.id == modelId } ?: return false
        val modelFile = File(modelsDir, model.filename)
        return modelFile.delete()
    }

    /**
     * Get total size of all downloaded models.
     */
    fun getTotalModelsSize(): Long {
        return modelsDir.listFiles()?.sumOf { it.length() } ?: 0
    }

    /**
     * Reset download state to idle.
     */
    fun resetState() {
        _downloadState.value = DownloadState.Idle
    }
}
