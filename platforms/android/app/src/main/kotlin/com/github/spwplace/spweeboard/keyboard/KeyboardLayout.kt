package com.github.spwplace.spweeboard.keyboard

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Main keyboard layout composable.
 */
@Composable
fun KeyboardLayout(
    viewModel: KeyboardViewModel,
    onCommit: (String) -> Unit,
    onDelete: () -> Unit
) {
    val buffer by viewModel.buffer
    val preview by viewModel.preview
    val showQwerty by viewModel.showQwerty

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(4.dp)
    ) {
        // Buffer display and preview
        BufferDisplay(
            buffer = buffer,
            preview = preview,
            onClear = { viewModel.clear() }
        )

        Spacer(modifier = Modifier.height(4.dp))

        // SPW symbol rows
        SpwSymbolRows(
            onSymbolTap = { viewModel.pushSymbol(it) }
        )

        Spacer(modifier = Modifier.height(4.dp))

        if (showQwerty) {
            // QWERTY keyboard
            QwertyLayout(
                onKeyTap = { viewModel.pushChar(it) },
                onBackspace = { viewModel.pop() },
                onSpace = { viewModel.pushChar(" ") },
                onReturn = { onCommit(buffer) }
            )
        } else {
            // Action row
            ActionRow(
                buffer = buffer,
                onCommit = { onCommit(buffer) },
                onToggleQwerty = { viewModel.toggleQwerty() },
                onBackspace = { viewModel.pop() }
            )
        }
    }
}

@Composable
private fun BufferDisplay(
    buffer: String,
    preview: String?,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = buffer.ifEmpty { "~" },
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = if (buffer.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )

            if (buffer.isNotEmpty()) {
                IconButton(
                    onClick = onClear,
                    modifier = Modifier.size(24.dp)
                ) {
                    Text(
                        text = "×",
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (preview != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun SpwSymbolRows(onSymbolTap: (String) -> Unit) {
    val symbols = listOf(
        listOf("~" to "potential", "#" to "vibration", "." to "ground", "?" to "wonder", "!" to "action"),
        listOf("*" to "value", "&" to "subject", "@" to "perspective", "^" to "integration")
    )

    val brackets = listOf(
        "<" to ">",
        "(" to ")",
        "[" to "]",
        "{" to "}"
    )

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Symbol rows
        symbols.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                row.forEach { (symbol, _) ->
                    SymbolKey(
                        symbol = symbol,
                        modifier = Modifier.weight(1f),
                        onClick = { onSymbolTap(symbol) }
                    )
                }
            }
        }

        // Bracket row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            brackets.forEach { (open, close) ->
                BracketKey(
                    open = open,
                    close = close,
                    modifier = Modifier.weight(1f),
                    onOpenTap = { onSymbolTap(open) },
                    onCloseTap = { onSymbolTap(close) }
                )
            }
        }
    }
}

@Composable
private fun SymbolKey(
    symbol: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val view = LocalView.current
    var isPressed by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .height(48.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = { onClick() }
                )
            },
        shape = RoundedCornerShape(6.dp),
        color = if (isPressed) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = symbol,
                fontSize = 22.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun BracketKey(
    open: String,
    close: String,
    modifier: Modifier = Modifier,
    onOpenTap: () -> Unit,
    onCloseTap: () -> Unit
) {
    val view = LocalView.current
    var isPressed by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .height(48.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = { onOpenTap() },
                    onLongPress = { onCloseTap() }
                )
            },
        shape = RoundedCornerShape(6.dp),
        color = if (isPressed) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row {
                Text(
                    text = open,
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = close,
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    buffer: String,
    onCommit: () -> Unit,
    onToggleQwerty: () -> Unit,
    onBackspace: () -> Unit
) {
    val view = LocalView.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ABC toggle
        Surface(
            modifier = Modifier
                .height(44.dp)
                .width(60.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onToggleQwerty()
                        }
                    )
                },
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "ABC",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Backspace
        Surface(
            modifier = Modifier
                .height(44.dp)
                .width(50.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onBackspace()
                        }
                    )
                },
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(text = "⌫", fontSize = 20.sp)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Commit button
        Surface(
            modifier = Modifier
                .height(44.dp)
                .width(80.dp)
                .pointerInput(buffer.isNotEmpty()) {
                    if (buffer.isNotEmpty()) {
                        detectTapGestures(
                            onTap = {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                onCommit()
                            }
                        )
                    }
                },
            shape = RoundedCornerShape(6.dp),
            color = if (buffer.isNotEmpty()) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "↑",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (buffer.isNotEmpty()) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    }
                )
            }
        }
    }
}

@Composable
private fun QwertyLayout(
    onKeyTap: (String) -> Unit,
    onBackspace: () -> Unit,
    onSpace: () -> Unit,
    onReturn: () -> Unit
) {
    val view = LocalView.current
    val rows = listOf(
        "qwertyuiop",
        "asdfghjkl",
        "zxcvbnm"
    )

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                row.forEach { char ->
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        onKeyTap(char.toString())
                                    }
                                )
                            },
                        shape = RoundedCornerShape(5.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = char.toString(),
                                fontSize = 20.sp
                            )
                        }
                    }
                }
            }
        }

        // Bottom row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // Backspace
            Surface(
                modifier = Modifier
                    .weight(1.5f)
                    .height(42.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                onBackspace()
                            }
                        )
                    },
                shape = RoundedCornerShape(5.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(text = "⌫", fontSize = 18.sp)
                }
            }

            // Space
            Surface(
                modifier = Modifier
                    .weight(5f)
                    .height(42.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                onSpace()
                            }
                        )
                    },
                shape = RoundedCornerShape(5.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                Box(contentAlignment = Alignment.Center) {}
            }

            // Return
            Surface(
                modifier = Modifier
                    .weight(1.5f)
                    .height(42.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                onReturn()
                            }
                        )
                    },
                shape = RoundedCornerShape(5.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(text = "↵", fontSize = 18.sp)
                }
            }
        }
    }
}
