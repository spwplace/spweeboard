package com.github.spwplace.spweeboard.keyboard

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.github.spwplace.spweeboard.ui.theme.SpweeboardTheme

/**
 * Main InputMethodService for spweebo'ard.
 * Renders a Compose-based keyboard UI.
 */
class SpweeboardService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val viewModel = KeyboardViewModel()

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onCreateInputView(): View {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)

        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@SpweeboardService)
            setViewTreeSavedStateRegistryOwner(this@SpweeboardService)

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
                        }
                    )
                }
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}

/**
 * View model for keyboard state.
 */
class KeyboardViewModel {
    val buffer = mutableStateOf("")
    val preview = mutableStateOf<String?>(null)
    val showQwerty = mutableStateOf(false)

    fun pushSymbol(symbol: String) {
        buffer.value += symbol
        updatePreview()
    }

    fun pushChar(char: String) {
        buffer.value += char
        updatePreview()
    }

    fun pop() {
        if (buffer.value.isNotEmpty()) {
            buffer.value = buffer.value.dropLast(1)
            updatePreview()
        }
    }

    fun clear() {
        buffer.value = ""
        preview.value = null
    }

    fun commit() {
        buffer.value = ""
        preview.value = null
    }

    fun toggleQwerty() {
        showQwerty.value = !showQwerty.value
    }

    private fun updatePreview() {
        // TODO: Integrate with Rust core for LLM inference
        // For now, simple placeholder interpretation
        preview.value = if (buffer.value.isEmpty()) null else interpretPlaceholder(buffer.value)
    }

    private fun interpretPlaceholder(input: String): String {
        val parts = input.map { char ->
            when (char) {
                '~' -> "becoming"
                '#' -> "resonance"
                '.' -> "grounded"
                '?' -> "wondering"
                '!' -> "asserting"
                '*' -> "valuing"
                '&' -> "subject"
                '@' -> "perspective"
                '^' -> "integrating"
                '<' -> "concept("
                '>' -> ")"
                '(' -> "scene("
                ')' -> ")"
                '[' -> "mode("
                ']' -> ")"
                '{' -> "toward("
                '}' -> ")"
                else -> char.toString()
            }
        }
        return parts.joinToString(" ")
    }
}
