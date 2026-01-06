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
import com.github.spwplace.spweeboard.model.InferenceManager
import com.github.spwplace.spweeboard.model.InterpretResult
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

    // Coroutine scope for the recomposer
    private var scope: CoroutineScope? = null
    private var recomposer: Recomposer? = null

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    private val viewModel = KeyboardViewModel()

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        // Create recomposer with our own scope and start it
        scope = CoroutineScope(SupervisorJob() + AndroidUiDispatcher.Main)
        recomposer = Recomposer(scope!!.coroutineContext)

        // Must launch the recomposer to process state changes
        scope!!.launch {
            recomposer!!.runRecomposeAndApplyChanges()
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
                SpweeboardTheme {
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
                        }
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
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        recomposer?.cancel()
        scope?.cancel()
        super.onDestroy()
    }
}

/**
 * View model for keyboard state.
 * Uses Rust FFI for SPW parsing and interpretation.
 */
class KeyboardViewModel {
    val buffer = mutableStateOf("")
    val parseState = mutableStateOf(ParseState.Empty)
    val interpretation = mutableStateOf<String?>(null)
    val interpretError = mutableStateOf<String?>(null)
    val isInterpreting = mutableStateOf(false)
    val selectedGround = mutableStateOf(defaultGrounds.first())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var interpretJob: kotlinx.coroutines.Job? = null

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
        isInterpreting.value = false
        interpretJob?.cancel()
    }

    fun commit() {
        buffer.value = ""
        parseState.value = ParseState.Empty
        interpretation.value = null
        interpretError.value = null
        isInterpreting.value = false
        interpretJob?.cancel()
    }

    fun selectGround(ground: GroundOption) {
        selectedGround.value = ground
        // Re-interpret with new ground
        if (parseState.value == ParseState.Valid) {
            interpretAsync(buffer.value, ground)
        }
    }

    private fun updateParseState() {
        val input = buffer.value

        if (input.isEmpty()) {
            parseState.value = ParseState.Empty
            interpretation.value = null
            interpretError.value = null
            isInterpreting.value = false
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

        if (isValid) {
            interpretAsync(input, selectedGround.value)
        } else {
            interpretation.value = null
            interpretError.value = null
            isInterpreting.value = false
        }
    }

    /**
     * Interpret SPW expression asynchronously.
     * Cancels any pending interpretation and runs on IO thread.
     */
    private fun interpretAsync(input: String, ground: GroundOption) {
        // Cancel previous job
        interpretJob?.cancel()

        // Check if model is loaded first (quick check)
        if (!InferenceManager.isModelLoaded()) {
            interpretation.value = null
            interpretError.value = "No model loaded"
            isInterpreting.value = false
            return
        }

        isInterpreting.value = true

        interpretJob = scope.launch {
            val groundName = if (ground.id != "none") ground.name else null

            // Run interpretation on IO thread
            val result = withContext(Dispatchers.IO) {
                InferenceManager.interpret(input, groundName)
            }

            // Update UI on main thread
            when (result) {
                is InterpretResult.Success -> {
                    interpretation.value = result.text
                    interpretError.value = null
                }
                is InterpretResult.Error -> {
                    interpretation.value = null
                    interpretError.value = result.message
                }
            }
            isInterpreting.value = false
        }
    }

    fun dispose() {
        scope.cancel()
    }
}
