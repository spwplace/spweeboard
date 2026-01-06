package com.github.spwplace.spweeboard.keyboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.spwplace.spweeboard.ui.theme.SpweeboardTheme

/**
 * Settings activity accessible from keyboard settings.
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpweeboardTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    KeyboardSettingsScreen(
                        onBack = { finish() }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardSettingsScreen(onBack: () -> Unit) {
    var hapticEnabled by remember { mutableStateOf(true) }
    var streamingEnabled by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("spweebo'ard Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Keyboard",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            ListItem(
                headlineContent = { Text("Haptic Feedback") },
                supportingContent = { Text("Vibrate on key press") },
                trailingContent = {
                    Switch(
                        checked = hapticEnabled,
                        onCheckedChange = { hapticEnabled = it }
                    )
                }
            )

            ListItem(
                headlineContent = { Text("Streaming Preview") },
                supportingContent = { Text("Show interpretation as you type") },
                trailingContent = {
                    Switch(
                        checked = streamingEnabled,
                        onCheckedChange = { streamingEnabled = it }
                    )
                }
            )

            HorizontalDivider()

            Text(
                text = "Model",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            ListItem(
                headlineContent = { Text("Active Model") },
                supportingContent = { Text("Qwen2.5-0.5B-Instruct") }
            )

            ListItem(
                headlineContent = { Text("Model Status") },
                supportingContent = { Text("Not loaded") }
            )

            HorizontalDivider()

            Text(
                text = "SPW Symbols",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "~ potential  # vibration  . ground",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "? wonder    ! action     * value",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "& subject   @ perspective ^ integration",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "<> concept  () scene  [] mode  {} direction",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
