package com.github.spwplace.spweeboard.keyboard

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import com.github.spwplace.spweeboard.MainActivity
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.compositionContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.github.spwplace.spweeboard.ui.theme.SpweeboardTheme
import com.github.spwplace.spweeboard.settings.SettingsRepository
import com.github.spwplace.spweeboard.settings.ThemeMode
import com.github.spwplace.spweeboard.model.InferenceManager
import com.github.spwplace.spweeboard.model.InterpretResult
import com.github.spwplace.spweeboard.model.StreamingResult
import com.github.spwplace.spweeboard.FeatureFlags
import com.github.spwplace.spweeboard.SpweeboardApplication
import uniffi.spweeboard_core.SpwGroundStore
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main InputMethodService for spweebo'ard.
 * Renders a Compose-based keyboard UI.
 */
class SpweeboardService : InputMethodService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    // Single coroutine scope for service lifecycle (recomposer, settings, etc.)
    private var serviceScope: CoroutineScope? = null
    private var recomposer: Recomposer? = null

    // Settings for persistence
    private lateinit var settingsRepository: SettingsRepository

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    private val viewModel by lazy {
        KeyboardViewModel(
            onGroundSelected = { groundId ->
                serviceScope?.launch {
                    settingsRepository.setDefaultGroundId(groundId)
                }
            }
        )
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        // Create single coroutine scope for service lifecycle
        serviceScope = CoroutineScope(SupervisorJob() + AndroidUiDispatcher.Main)

        // Initialize settings repository
        settingsRepository = SettingsRepository.getInstance(this)

        // Load saved ground selection
        serviceScope?.launch {
            settingsRepository.settings.collect { settings ->
                viewModel.initializeGround(settings.defaultGroundId)
            }
        }

        // Create recomposer with service scope and start it
        recomposer = Recomposer(serviceScope!!.coroutineContext)

        // Must launch the recomposer to process state changes
        serviceScope?.launch {
            recomposer?.runRecomposeAndApplyChanges()
        }
    }

    override fun onCreateInputView(): View {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)

        return ComposeView(this).apply {
            // Use DisposeOnDetachedFromWindowOrReleasedFromPool for IME
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)

            // Set view tree owners BEFORE setting content
            setViewTreeLifecycleOwner(this@SpweeboardService)
            setViewTreeSavedStateRegistryOwner(this@SpweeboardService)
            setViewTreeViewModelStoreOwner(this@SpweeboardService)

            // Use our custom recomposer
            compositionContext = recomposer

            setContent {
                // Read settings
                val settingsRepository = remember { SettingsRepository.getInstance(this@SpweeboardService) }
                val themeMode by settingsRepository.themeMode.collectAsState(initial = ThemeMode.System)
                val hapticEnabled by settingsRepository.hapticEnabled.collectAsState(initial = true)

                SpweeboardTheme(themeMode = themeMode) {
                    KeyboardLayout(
                        viewModel = viewModel,
                        onCommit = { text ->
                            currentInputConnection?.commitText(text, 1)
                            viewModel.commit()
                        },
                        onDelete = {
                            currentInputConnection?.deleteSurroundingText(1, 0)
                        },
                        onOpenSettings = {
                            // Launch main app for setup
                            val intent = Intent(this@SpweeboardService, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)
                        },
                        hapticEnabled = hapticEnabled
                    )
                }
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // Cancel any in-progress streaming when keyboard hides
        viewModel.cancelInterpretation()
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
    }

    override fun onDestroy() {
        viewModel.dispose()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        // Cancel recomposer first, then cancel its scope
        recomposer?.cancel()
        serviceScope?.cancel()
        serviceScope = null
        super.onDestroy()
    }
}

/**
 * View model for keyboard state.
 * Uses Rust FFI for SPW parsing and interpretation.
 */
class KeyboardViewModel(
    private val groundStore: SpwGroundStore? = SpweeboardApplication.getGroundStore(),
    private val onGroundSelected: ((String) -> Unit)? = null
) {
    val buffer = mutableStateOf("")
    val parseState = mutableStateOf(ParseState.Empty)
    val interpretation = mutableStateOf<String?>(null)
    val interpretError = mutableStateOf<String?>(null)
    val loadingState = mutableStateOf<LoadingState>(LoadingState.Idle)
    val selectedGround = mutableStateOf(defaultGrounds.first())

    // Streaming state
    val streamingText = mutableStateOf<String?>(null)
    val isThinking = mutableStateOf(false)

    // Pending commit callback - called when interpretation completes after send
    private var pendingCommit: ((String) -> Unit)? = null

    // Expression history - backed by Rust store for persistence
    val history: List<String> get() = groundStore?.listHistory(FeatureFlags.MAX_HISTORY_SIZE.toUInt())
        ?: emptyList()
    val isHistoryVisible = mutableStateOf(false)

    // Available grounds - preset + custom from shared store
    val availableGrounds: List<GroundOption>
        get() {
            val customGrounds = groundStore?.list()?.map { GroundOption.fromRust(it) } ?: emptyList()
            return defaultGrounds + customGrounds
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var streamingJob: kotlinx.coroutines.Job? = null

    init {
        // Collect streaming results
        streamingJob = scope.launch {
            InferenceManager.streamingResults.collect { result ->
                when (result) {
                    is StreamingResult.Thinking -> {
                        isThinking.value = true
                    }
                    is StreamingResult.Chunk -> {
                        isThinking.value = false
                        val current = streamingText.value ?: ""
                        streamingText.value = current + result.text
                    }
                    is StreamingResult.Complete -> {
                        isThinking.value = false
                        interpretation.value = result.fullText
                        streamingText.value = null
                        loadingState.value = LoadingState.Idle
                        // If there's a pending commit, execute it and clear state
                        if (pendingCommit != null) {
                            pendingCommit?.invoke(result.fullText)
                            pendingCommit = null
                            commit()  // Clear buffer and save to history
                        }
                    }
                    is StreamingResult.Error -> {
                        isThinking.value = false
                        interpretError.value = result.message
                        streamingText.value = null
                        loadingState.value = LoadingState.Idle
                        pendingCommit = null
                    }
                }
            }
        }
    }

    fun pushSymbol(symbol: String) {
        buffer.value += symbol
        updateParseState()
    }

    fun pushChar(char: String) {
        buffer.value += char
        updateParseState()
    }

    fun pop() {
        if (buffer.value.isNotEmpty()) {
            buffer.value = buffer.value.dropLast(1)
            updateParseState()
        }
    }

    fun clear() {
        buffer.value = ""
        parseState.value = ParseState.Empty
        interpretation.value = null
        interpretError.value = null
        streamingText.value = null
        isThinking.value = false
        loadingState.value = LoadingState.Idle
        pendingCommit = null
        InferenceManager.cancelInterpretation()
    }

    /**
     * Initiate send: start interpretation and commit when complete.
     * @param onCommit Called with the interpreted text when ready to commit
     */
    fun send(onCommit: (String) -> Unit) {
        if (parseState.value != ParseState.Valid || buffer.value.isEmpty()) {
            return
        }

        // If already loading, don't start another interpretation
        if (loadingState.value.isLoading) {
            return
        }

        // Check if model is loaded
        if (!InferenceManager.isModelLoaded()) {
            interpretError.value = "No model loaded"
            return
        }

        // Set pending commit and start interpretation
        pendingCommit = onCommit
        interpretAsync(buffer.value, selectedGround.value)
    }

    fun commit() {
        // Add to history if enabled and buffer is non-empty
        if (FeatureFlags.historyEnabled && buffer.value.isNotEmpty()) {
            // Push to Rust store (handles deduplication and trimming)
            groundStore?.pushHistory(buffer.value)
        }

        buffer.value = ""
        parseState.value = ParseState.Empty
        interpretation.value = null
        interpretError.value = null
        streamingText.value = null
        isThinking.value = false
        loadingState.value = LoadingState.Idle
        pendingCommit = null
    }

    /**
     * Recall an expression from history.
     * @param expression The expression string to recall
     */
    fun recall(expression: String) {
        buffer.value = expression
        updateParseState()
        isHistoryVisible.value = false
    }

    /**
     * Show/hide the history sheet.
     */
    fun toggleHistory() {
        if (FeatureFlags.historyEnabled) {
            isHistoryVisible.value = !isHistoryVisible.value
        }
    }

    fun hideHistory() {
        isHistoryVisible.value = false
    }

    /**
     * Clear all history entries.
     */
    fun clearHistory() {
        groundStore?.clearHistory()
    }

    fun selectGround(ground: GroundOption) {
        selectedGround.value = ground
        // Persist ground selection
        onGroundSelected?.invoke(ground.id)
        // Clear any previous interpretation since ground changed
        interpretation.value = null
        interpretError.value = null
    }

    /**
     * Initialize the selected ground from a persisted ID.
     * Searches both preset and custom grounds.
     */
    fun initializeGround(groundId: String?) {
        if (groundId != null) {
            val ground = availableGrounds.find { it.id == groundId }
            if (ground != null) {
                selectedGround.value = ground
            }
        }
    }

    private fun updateParseState() {
        val input = buffer.value

        if (input.isEmpty()) {
            parseState.value = ParseState.Empty
            interpretation.value = null
            interpretError.value = null
            loadingState.value = LoadingState.Idle
            return
        }

        // Use Rust FFI for parsing (this is fast, ok to run sync)
        val isValid = try {
            uniffi.spweeboard_core.validateSpw(input)
        } catch (e: Exception) {
            // Native library not loaded
            interpretError.value = "Native library error: ${e.message}"
            false
        }

        parseState.value = if (isValid) ParseState.Valid else ParseState.Invalid

        // Don't auto-interpret on every keystroke - only clear errors for invalid state
        if (!isValid) {
            interpretation.value = null
            interpretError.value = null
            loadingState.value = LoadingState.Idle
        }
    }

    /**
     * Interpret SPW expression asynchronously using streaming.
     * Cancels any pending interpretation and starts a new streaming session.
     */
    private fun interpretAsync(input: String, ground: GroundOption) {
        // Cancel previous interpretation
        InferenceManager.cancelInterpretation()

        // Reset streaming state
        streamingText.value = null
        isThinking.value = false
        interpretation.value = null
        interpretError.value = null

        // Check if model is loaded first (quick check)
        loadingState.value = LoadingState.CheckingModel
        if (!InferenceManager.isModelLoaded()) {
            interpretError.value = "No model loaded"
            loadingState.value = LoadingState.Idle
            return
        }

        loadingState.value = LoadingState.Interpreting

        val groundName = if (ground.id != "none") ground.name else null

        // Start streaming interpretation (non-blocking)
        // Results will be collected via the streamingResults flow
        InferenceManager.interpretStreaming(input, groundName)
    }

    /**
     * Cancel any in-progress interpretation.
     */
    fun cancelInterpretation() {
        if (FeatureFlags.cancelInterpretationEnabled && loadingState.value.isLoading) {
            InferenceManager.cancelInterpretation()
            loadingState.value = LoadingState.Idle
            interpretation.value = null
            interpretError.value = null
            streamingText.value = null
            isThinking.value = false
            pendingCommit = null
        }
    }

    fun dispose() {
        streamingJob?.cancel()
        scope.cancel()
    }
}
