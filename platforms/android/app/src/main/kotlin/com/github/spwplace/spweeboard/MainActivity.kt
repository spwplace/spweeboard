package com.github.spwplace.spweeboard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.spwplace.spweeboard.ui.theme.SpweeboardTheme

/**
 * Main container app activity.
 * Provides setup instructions and ground management.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpweeboardTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("spweebo'ard") }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Outlined.Keyboard, contentDescription = "Setup") },
                    label = { Text("Setup") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Outlined.Layers, contentDescription = "Grounds") },
                    label = { Text("Grounds") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Outlined.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )
            }
        }
    ) { padding ->
        when (selectedTab) {
            0 -> SetupScreen(Modifier.padding(padding))
            1 -> GroundsScreen(Modifier.padding(padding))
            2 -> SettingsScreen(Modifier.padding(padding))
        }
    }
}

@Composable
fun SetupScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Symbolic Keyboard",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = "Compose thoughts using SPW cognitive primitives, interpreted by on-device AI.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Enable Keyboard",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )

        SetupStep(
            number = 1,
            title = "Enable Input Method",
            description = "Go to Settings → System → Languages & input → On-screen keyboard"
        )

        SetupStep(
            number = 2,
            title = "Select spweebo'ard",
            description = "Enable spweebo'ard in the list of available keyboards"
        )

        SetupStep(
            number = 3,
            title = "Switch Keyboards",
            description = "Use the keyboard switcher to select spweebo'ard when typing"
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open Keyboard Settings")
        }

        OutlinedButton(
            onClick = {
                val imm = context.getSystemService(InputMethodManager::class.java)
                imm.showInputMethodPicker()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Switch Keyboard")
        }
    }
}

@Composable
fun SetupStep(number: Int, title: String, description: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = number.toString(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun GroundsScreen(modifier: Modifier = Modifier) {
    // TODO: Implement ground library UI
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Ground library coming soon",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
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
            trailingContent = {
                Switch(checked = true, onCheckedChange = {})
            }
        )

        ListItem(
            headlineContent = { Text("Streaming Preview") },
            trailingContent = {
                Switch(checked = true, onCheckedChange = {})
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
            supportingContent = { Text("Qwen2.5-0.5B") }
        )

        HorizontalDivider()

        Text(
            text = "About",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )

        ListItem(
            headlineContent = { Text("Version") },
            supportingContent = { Text("0.1.0") }
        )
    }
}

// Icon placeholders (normally would use material-icons-extended)
private object Icons {
    object Outlined {
        val Keyboard = androidx.compose.material.icons.Icons.Default.KeyboardAlt
        val Layers = androidx.compose.material.icons.Icons.Default.Layers
        val Settings = androidx.compose.material.icons.Icons.Default.Settings
    }
}
