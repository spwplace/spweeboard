package com.github.spwplace.spweeboard.keyboard

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.spwplace.spweeboard.FeatureFlags

/**
 * SPW symbols loaded from Rust core.
 * Split into two rows for the keyboard layout.
 */
private val spwSymbols: List<List<uniffi.spweeboard_core.SpwSymbolInfo>> by lazy {
    val all = uniffi.spweeboard_core.getSymbols()
    // Split into two rows: first 5, then remaining 4
    listOf(all.take(5), all.drop(5))
}

/**
 * SPW brackets loaded from Rust core.
 */
private val spwBrackets: List<uniffi.spweeboard_core.SpwBracketInfo> by lazy {
    uniffi.spweeboard_core.getBrackets()
}

/**
 * Main keyboard layout composable.
 * Shows SPW symbol rows above a QWERTY keyboard, with buffer display and ground selector.
 */
@Composable
fun KeyboardLayout(
    viewModel: KeyboardViewModel,
    onCommit: (String) -> Unit,
    onDelete: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    hapticEnabled: Boolean = true
) {
    val buffer by viewModel.buffer
    val parseState by viewModel.parseState
    val interpretation by viewModel.interpretation
    val interpretError by viewModel.interpretError
    val loadingState by viewModel.loadingState
    val selectedGround by viewModel.selectedGround
    val isHistoryVisible by viewModel.isHistoryVisible

    // Can only send if we have a valid interpretation (no errors, not loading)
    val canSend = parseState == ParseState.Valid && buffer.isNotEmpty() && interpretation != null && !loadingState.isLoading

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 4.dp, vertical = 6.dp)
        ) {
            // Buffer display with parse status and error
            BufferDisplay(
                buffer = buffer,
                parseState = parseState,
                interpretation = interpretation,
                error = interpretError,
                loadingState = loadingState,
                onClear = { viewModel.clear() },
                onCancel = { viewModel.cancelInterpretation() },
                onErrorTap = onOpenSettings
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Ground selector row with optional history button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // History button (if enabled and has history)
                if (FeatureFlags.historyEnabled) {
                    HistoryButton(
                        historyCount = viewModel.history.size,
                        onClick = { viewModel.toggleHistory() }
                    )
                }

                // Ground selector takes remaining space
                Box(modifier = Modifier.weight(1f)) {
                    GroundSelectorRow(
                        availableGrounds = viewModel.availableGrounds,
                        selectedGround = selectedGround,
                        onGroundSelected = { viewModel.selectGround(it) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // SPW symbol rows (2 rows)
            SpwSymbolRows(
                onSymbolTap = { viewModel.pushSymbol(it) },
                hapticEnabled = hapticEnabled
            )

            Spacer(modifier = Modifier.height(4.dp))

            // QWERTY keyboard (always visible)
            QwertyLayout(
                onKeyTap = { viewModel.pushChar(it) },
                onBackspace = { viewModel.pop() },
                onSpace = { viewModel.pushChar(" ") },
                onSend = {
                    if (canSend && interpretation != null) {
                        onCommit(interpretation!!)
                    }
                },
                canSend = canSend,
                hapticEnabled = hapticEnabled
            )
        }

        // History sheet overlay
        if (FeatureFlags.historyEnabled) {
            HistorySheet(
                isVisible = isHistoryVisible,
                history = viewModel.history,
                onRecall = { viewModel.recall(it) },
                onDismiss = { viewModel.hideHistory() },
                onClearHistory = { viewModel.clearHistory() },
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

/**
 * Parse state for SPW expression.
 */
enum class ParseState {
    Empty,
    Valid,
    Invalid
}

/**
 * Loading state for interpretation process.
 * Provides more granular feedback than a simple boolean.
 */
sealed class LoadingState {
    /** No loading in progress */
    data object Idle : LoadingState()

    /** Checking model availability */
    data object CheckingModel : LoadingState()

    /** Interpretation is running */
    data object Interpreting : LoadingState()

    /** Streaming tokens (future use) */
    data class Streaming(val tokensReceived: Int, val partialText: String) : LoadingState()

    val isLoading: Boolean get() = this !is Idle
}

/**
 * Available grounds for interpretation context.
 */
data class GroundOption(
    val id: String,
    val name: String,
    val spw: String
) {
    companion object {
        fun fromRust(rust: uniffi.spweeboard_core.SpwGround): GroundOption {
            return GroundOption(
                id = rust.id,
                name = rust.name,
                spw = rust.content
            )
        }
    }
}

/**
 * Default grounds - loaded from Rust core with "None" option prepended.
 */
val defaultGrounds: List<GroundOption> by lazy {
    listOf(GroundOption("none", "None", "")) +
        uniffi.spweeboard_core.presetGrounds().map { GroundOption.fromRust(it) }
}

@Composable
private fun BufferDisplay(
    buffer: String,
    parseState: ParseState,
    interpretation: String?,
    error: String?,
    loadingState: LoadingState = LoadingState.Idle,
    onClear: () -> Unit,
    onCancel: () -> Unit = {},
    onErrorTap: (() -> Unit)? = null
) {
    val isLoading = loadingState.isLoading

    // Show error state if there's an error
    val hasError = error != null && parseState == ParseState.Valid && !isLoading

    // Shimmer animation for loading state
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )

    // Animate border color based on parse state and error
    val borderColor by animateColorAsState(
        targetValue = when {
            isLoading -> MaterialTheme.colorScheme.primary.copy(alpha = shimmerAlpha)
            hasError -> MaterialTheme.colorScheme.error
            parseState == ParseState.Empty -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            parseState == ParseState.Valid -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.error
        },
        animationSpec = tween(200),
        label = "borderColor"
    )

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isLoading -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
            hasError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            parseState == ParseState.Empty -> MaterialTheme.colorScheme.surfaceContainerHigh
            parseState == ParseState.Valid -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        },
        animationSpec = tween(200),
        label = "backgroundColor"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(2.dp, borderColor, RoundedCornerShape(10.dp))
            .background(backgroundColor)
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // SPW expression
            Text(
                text = buffer.ifEmpty { "type SPW expression..." },
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                ),
                color = if (buffer.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                } else when {
                    hasError -> MaterialTheme.colorScheme.error
                    parseState == ParseState.Valid -> MaterialTheme.colorScheme.onSurface
                    parseState == ParseState.Invalid -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (buffer.isNotEmpty()) {
                // Parse status indicator with pulsing effect when loading
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isLoading -> MaterialTheme.colorScheme.primary.copy(alpha = shimmerAlpha)
                                hasError -> MaterialTheme.colorScheme.error
                                parseState == ParseState.Valid && interpretation != null -> Color(0xFF4CAF50)
                                parseState == ParseState.Valid -> Color(0xFFFFA000) // Orange: valid but no interpretation
                                parseState == ParseState.Invalid -> MaterialTheme.colorScheme.error
                                else -> Color.Transparent
                            }
                        )
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Clear button
                Text(
                    text = "×",
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onClear() }
                )
            }
        }

        // Show loading indicator with state-specific message
        if (isLoading) {
            Spacer(modifier = Modifier.height(6.dp))
            val loadingText = when (loadingState) {
                is LoadingState.CheckingModel -> "Checking model..."
                is LoadingState.Interpreting -> "Interpreting..."
                is LoadingState.Streaming -> {
                    val tokens = loadingState.tokensReceived
                    "Generating ($tokens tokens)..."
                }
                else -> "Loading..."
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Pulsing dot indicator
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = shimmerAlpha))
                    )
                    Text(
                        text = loadingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    )
                }
                // Cancel button (if enabled)
                if (FeatureFlags.cancelInterpretationEnabled) {
                    Surface(
                        onClick = onCancel,
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                    ) {
                        Text(
                            text = "Cancel",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            // Show partial streaming text if available
            if (loadingState is LoadingState.Streaming && loadingState.partialText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "→ ${loadingState.partialText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // Show error message (tappable to open settings)
        else if (hasError && error != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onErrorTap != null) Modifier.clickable { onErrorTap() }
                        else Modifier
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⚠ $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (onErrorTap != null) {
                    Text(
                        text = "Setup →",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        // Show interpretation preview
        else if (interpretation != null && parseState == ParseState.Valid) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "→ $interpretation",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun GroundSelectorRow(
    availableGrounds: List<GroundOption>,
    selectedGround: GroundOption,
    onGroundSelected: (GroundOption) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        availableGrounds.forEach { ground ->
            val isSelected = ground.id == selectedGround.id
            Surface(
                modifier = Modifier
                    .height(28.dp)
                    .clickable { onGroundSelected(ground) },
                shape = RoundedCornerShape(14.dp),
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                }
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = ground.name,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SpwSymbolRows(
    onSymbolTap: (String) -> Unit,
    hapticEnabled: Boolean = true
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Symbol rows (loaded from Rust)
        spwSymbols.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                row.forEach { symbolInfo ->
                    SymbolKey(
                        symbol = symbolInfo.charRepr,
                        modifier = Modifier.weight(1f),
                        onClick = { onSymbolTap(symbolInfo.charRepr) },
                        hapticEnabled = hapticEnabled
                    )
                }
            }
        }

        // Bracket row (loaded from Rust)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            spwBrackets.forEach { bracketInfo ->
                BracketKey(
                    open = bracketInfo.open,
                    close = bracketInfo.close,
                    modifier = Modifier.weight(1f),
                    onOpenTap = { onSymbolTap(bracketInfo.open) },
                    onCloseTap = { onSymbolTap(bracketInfo.close) },
                    hapticEnabled = hapticEnabled
                )
            }
        }
    }
}

@Composable
private fun SymbolKey(
    symbol: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    hapticEnabled: Boolean = true
) {
    val view = LocalView.current

    Surface(
        onClick = {
            if (hapticEnabled) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            onClick()
        },
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
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
    onCloseTap: () -> Unit,
    hapticEnabled: Boolean = true
) {
    val view = LocalView.current

    // Two side-by-side buttons for open and close brackets
    Row(
        modifier = modifier.height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Surface(
            onClick = {
                if (hapticEnabled) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                onOpenTap()
            },
            modifier = Modifier.weight(1f).fillMaxHeight(),
            shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = open,
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Surface(
            onClick = {
                if (hapticEnabled) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                onCloseTap()
            },
            modifier = Modifier.weight(1f).fillMaxHeight(),
            shape = RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Box(contentAlignment = Alignment.Center) {
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
private fun QwertyLayout(
    onKeyTap: (String) -> Unit,
    onBackspace: () -> Unit,
    onSpace: () -> Unit,
    onSend: () -> Unit,
    canSend: Boolean,
    hapticEnabled: Boolean = true
) {
    val view = LocalView.current
    val rows = listOf(
        "qwertyuiop",
        "asdfghjkl",
        "zxcvbnm"
    )

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEachIndexed { index, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                // Add padding on sides for middle rows
                if (index == 1) Spacer(modifier = Modifier.width(16.dp))
                if (index == 2) Spacer(modifier = Modifier.width(32.dp))

                row.forEach { char ->
                    Surface(
                        onClick = {
                            if (hapticEnabled) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                            onKeyTap(char.toString())
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shadowElevation = 1.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = char.toString(),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                if (index == 1) Spacer(modifier = Modifier.width(16.dp))
                if (index == 2) Spacer(modifier = Modifier.width(32.dp))
            }
        }

        // Bottom row with backspace, space, and send
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Backspace
            Surface(
                onClick = {
                    if (hapticEnabled) {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                    onBackspace()
                },
                modifier = Modifier
                    .weight(1.2f)
                    .height(46.dp),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 1.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(text = "⌫", fontSize = 20.sp)
                }
            }

            // Space bar
            Surface(
                onClick = {
                    if (hapticEnabled) {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                    onSpace()
                },
                modifier = Modifier
                    .weight(4f)
                    .height(46.dp),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shadowElevation = 1.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "space",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }

            // Send button - animated based on canSend
            val sendScale by animateFloatAsState(
                targetValue = if (canSend) 1f else 0.95f,
                animationSpec = tween(150),
                label = "sendScale"
            )

            Surface(
                onClick = {
                    if (canSend) {
                        if (hapticEnabled) {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        onSend()
                    }
                },
                modifier = Modifier
                    .weight(1.5f)
                    .height(46.dp)
                    .scale(sendScale),
                shape = RoundedCornerShape(6.dp),
                color = if (canSend) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                shadowElevation = if (canSend) 2.dp else 0.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "↑",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (canSend) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        }
                    )
                }
            }
        }
    }
}
