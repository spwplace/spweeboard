package com.github.spwplace.spweeboard.model

import android.content.Context
import android.util.Log
import com.github.spwplace.spweeboard.settings.InferenceParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import uniffi.spweeboard_core.SpwEngineStatus
import uniffi.spweeboard_core.SpwInferenceConfig
import uniffi.spweeboard_core.SpwInferenceEngine
import uniffi.spweeboard_core.SpwInferenceResult

/**
 * Singleton manager for LLM inference.
 * Handles model loading and provides interpretation services.
 */
object InferenceManager {
    private const val TAG = "InferenceManager"

    private var engine: SpwInferenceEngine? = null
    private var currentModelPath: String? = null

    private val _status = MutableStateFlow<InferenceStatus>(InferenceStatus.NotLoaded)
    val status: StateFlow<InferenceStatus> = _status.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * Initialize the engine (call once on app startup).
     */
    fun initialize() {
        if (engine == null) {
            try {
                engine = SpwInferenceEngine()
                Log.i(TAG, "Inference engine created")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create inference engine", e)
                _status.value = InferenceStatus.Error("Failed to initialize: ${e.message}")
            }
        }
    }

    /**
     * Load a model from the given path.
     * Should be called from a coroutine.
     * @param modelPath Path to the GGUF model file
     * @param params Optional inference parameters (uses defaults if not provided)
     */
    suspend fun loadModel(
        modelPath: String,
        params: InferenceParams = InferenceParams()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val eng = engine ?: return@withContext Result.failure(Exception("Engine not initialized"))

        _isLoading.value = true
        _status.value = InferenceStatus.Loading

        try {
            val config = SpwInferenceConfig(
                modelPath = modelPath,
                nThreads = 4u,
                nCtx = 2048u,
                useGpu = true,
                nGpuLayers = 99u,
                maxTokens = params.maxTokens.toUInt(),
                temperature = params.temperature,
                topP = params.topP,
                topK = params.topK.toUInt(),
                repeatPenalty = params.repeatPenalty
            )

            val result = eng.loadModel(config)

            if (result.success) {
                currentModelPath = modelPath
                _status.value = InferenceStatus.Loaded(modelPath)
                Log.i(TAG, "Model loaded: $modelPath")
                Result.success(Unit)
            } else {
                val error = result.error ?: "Unknown error"
                _status.value = InferenceStatus.Error(error)
                Log.e(TAG, "Model load failed: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            _status.value = InferenceStatus.Error(e.message ?: "Load failed")
            Log.e(TAG, "Model load exception", e)
            Result.failure(e)
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Unload the current model.
     */
    fun unloadModel() {
        engine?.unloadModel()
        currentModelPath = null
        _status.value = InferenceStatus.NotLoaded
        Log.i(TAG, "Model unloaded")
    }

    /**
     * Cancel any in-progress interpretation.
     */
    fun cancelInterpretation() {
        try {
            engine?.cancel()
            Log.i(TAG, "Interpretation cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel interpretation", e)
        }
    }

    /**
     * Interpret an SPW expression using the LLM.
     * Returns a result with either the interpretation or an error message.
     */
    fun interpret(spwInput: String, groundName: String?): InterpretResult {
        val eng = engine
        if (eng == null) {
            return InterpretResult.Error("Engine not initialized")
        }

        if (_status.value !is InferenceStatus.Loaded) {
            return InterpretResult.Error("No model loaded")
        }

        return try {
            Log.d(TAG, "Interpreting: '$spwInput' with ground: $groundName")
            val result = eng.interpret(spwInput, groundName)
            Log.d(TAG, "Rust result: success=${result.success}, text=${result.text}, error=${result.error}")

            if (result.success && result.text != null) {
                InterpretResult.Success(result.text!!)
            } else {
                val error = result.error ?: "Inference returned no result"
                // Check if this was a cancellation
                if (error.contains("cancelled", ignoreCase = true)) {
                    Log.i(TAG, "Inference was cancelled")
                    InterpretResult.Cancelled
                } else {
                    Log.w(TAG, "Inference failed: $error")
                    InterpretResult.Error(error)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inference exception", e)
            InterpretResult.Error("Exception: ${e.message}")
        }
    }

    /**
     * Check if a model is currently loaded.
     */
    fun isModelLoaded(): Boolean {
        return _status.value is InferenceStatus.Loaded
    }

    /**
     * Get the current engine status from Rust.
     */
    fun getEngineStatus(): SpwEngineStatus? {
        return try {
            engine?.getStatus()
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Status of the inference engine.
 */
sealed class InferenceStatus {
    data object NotLoaded : InferenceStatus()
    data object Loading : InferenceStatus()
    data class Loaded(val modelPath: String) : InferenceStatus()
    data class Error(val message: String) : InferenceStatus()
}

/**
 * Result of an interpretation request.
 */
sealed class InterpretResult {
    data class Success(val text: String) : InterpretResult()
    data class Error(val message: String) : InterpretResult()
    data object Cancelled : InterpretResult()
}
