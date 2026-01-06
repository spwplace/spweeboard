package com.github.spwplace.spweeboard.grounds

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import uniffi.spweeboard_core.SpwGround
import uniffi.spweeboard_core.SpwGroundContentType
import uniffi.spweeboard_core.validateSpwGround
import java.util.UUID

/**
 * Dialog for creating or editing a custom ground.
 */
@Composable
fun GroundEditorDialog(
    existingGround: SpwGround? = null,
    onDismiss: () -> Unit,
    onSave: (SpwGround) -> Unit
) {
    var name by remember { mutableStateOf(existingGround?.name ?: "") }
    var content by remember { mutableStateOf(existingGround?.content ?: "") }
    var isSpw by remember {
        mutableStateOf(existingGround?.contentType == SpwGroundContentType.SPW)
    }

    // Validation state
    val nameError = name.isBlank()
    val contentError = content.isBlank()
    val spwValidationError = if (isSpw && content.isNotBlank()) {
        try {
            validateSpwGround(content)
        } catch (e: Exception) {
            "Validation error: ${e.message}"
        }
    } else null

    val canSave = !nameError && !contentError && spwValidationError == null

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Title
                Text(
                    text = if (existingGround != null) "Edit Ground" else "Create Ground",
                    style = MaterialTheme.typography.headlineSmall
                )

                // Name field
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("e.g., Work Mode") },
                    isError = nameError && name.isNotEmpty(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Content type toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Type:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    FilterChip(
                        selected = !isSpw,
                        onClick = { isSpw = false },
                        label = { Text("Natural") }
                    )
                    FilterChip(
                        selected = isSpw,
                        onClick = { isSpw = true },
                        label = { Text("SPW") }
                    )
                }

                // Content field
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(if (isSpw) "SPW Expression" else "Description") },
                    placeholder = {
                        Text(
                            if (isSpw) "e.g., @[work].{utility}"
                            else "e.g., software development context"
                        )
                    },
                    isError = (contentError && content.isNotEmpty()) || spwValidationError != null,
                    supportingText = if (spwValidationError != null) {
                        { Text(spwValidationError, color = MaterialTheme.colorScheme.error) }
                    } else if (isSpw && content.isNotBlank()) {
                        { Text("Valid SPW expression", color = MaterialTheme.colorScheme.primary) }
                    } else null,
                    textStyle = if (isSpw) {
                        MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                // SPW help text
                if (isSpw) {
                    Text(
                        text = "SPW symbols: ~ (becoming) # (vibration) . (ground) ? (wonder) ! (action) * (value) & (self) @ (perspective) ^ (integration)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val ground = SpwGround(
                                id = existingGround?.id ?: UUID.randomUUID().toString(),
                                name = name.trim(),
                                contentType = if (isSpw) SpwGroundContentType.SPW else SpwGroundContentType.NATURAL,
                                content = content.trim(),
                                description = existingGround?.description ?: "",
                                category = existingGround?.category ?: "Custom"
                            )
                            onSave(ground)
                        },
                        enabled = canSave
                    ) {
                        Text(if (existingGround != null) "Save" else "Create")
                    }
                }
            }
        }
    }
}

/**
 * Confirmation dialog for deleting a ground.
 */
@Composable
fun DeleteGroundDialog(
    groundName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Ground") },
        text = { Text("Are you sure you want to delete \"$groundName\"? This action cannot be undone.") },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
