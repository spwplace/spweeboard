package com.github.spwplace.spweeboard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import com.github.spwplace.spweeboard.keyboard.KeyboardLayout
import com.github.spwplace.spweeboard.keyboard.KeyboardViewModel
import com.github.spwplace.spweeboard.model.DownloadState
import com.github.spwplace.spweeboard.model.InferenceManager
import com.github.spwplace.spweeboard.model.InferenceStatus
import com.github.spwplace.spweeboard.model.ModelDownloadManager
import com.github.spwplace.spweeboard.model.ModelInfo
import com.github.spwplace.spweeboard.settings.SettingsRepository
import com.github.spwplace.spweeboard.settings.ThemeMode
import com.github.spwplace.spweeboard.ui.theme.SpweeboardTheme
import com.github.spwplace.spweeboard.grounds.GroundEditorDialog
import com.github.spwplace.spweeboard.grounds.DeleteGroundDialog
import uniffi.spweeboard_core.SpwGround
import uniffi.spweeboard_core.SpwGroundStore
import uniffi.spweeboard_core.SpwGroundContentType
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Main container app activity.
 * Provides setup instructions and ground management.
 *
 * When launched from IME settings (via method.xml settingsActivity),
 * it will open directly to the Settings tab.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Check if launched from IME settings - open to Settings tab
        val startOnSettings = intent?.action == Intent.ACTION_MAIN &&
            intent?.categories?.contains(Intent.CATEGORY_LAUNCHER) != true

        setContent {
            val settingsRepository = remember { SettingsRepository.getInstance(this) }
            val settings by settingsRepository.settings.collectAsState(
                initial = com.github.spwplace.spweeboard.settings.SpweeboardSettings()
            )

            SpweeboardTheme(themeMode = settings.themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(startOnSettingsTab = startOnSettings)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(startOnSettingsTab: Boolean = false) {
    var selectedTab by remember { mutableIntStateOf(if (startOnSettingsTab) 2 else 0) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("spweebo'ard") }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Outlined.Create, contentDescription = "Setup") },
                    label = { Text("Setup") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Outlined.Home, contentDescription = "Grounds") },
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
    val scope = rememberCoroutineScope()
    val downloadManager = remember { ModelDownloadManager.getInstance(context) }
    val inferenceStatus by InferenceManager.status.collectAsState()
    val isLoadingModel by InferenceManager.isLoading.collectAsState()

    // Settings for inference params
    val settingsRepository = remember { SettingsRepository.getInstance(context) }
    val settings by settingsRepository.settings.collectAsState(
        initial = com.github.spwplace.spweeboard.settings.SpweeboardSettings()
    )

    // Track model status
    val defaultModel = ModelDownloadManager.DEFAULT_MODEL
    var modelStatus by remember { mutableStateOf(downloadManager.getModelStatus(defaultModel.id)) }

    // Observe WorkManager download state
    val downloadState by downloadManager.observeDownloadState(defaultModel.id)
        .collectAsState(initial = DownloadState.Idle)

    // Refresh status when download completes
    LaunchedEffect(downloadState) {
        if (downloadState is DownloadState.Completed) {
            modelStatus = downloadManager.getModelStatus(defaultModel.id)
            // Auto-load the model with current inference params
            val completedState = downloadState as DownloadState.Completed
            InferenceManager.loadModel(completedState.modelPath, settings.inferenceParams)
        }
    }

    // Determine setup completion states
    val isModelReady = inferenceStatus is InferenceStatus.Loaded
    val isModelDownloaded = modelStatus?.isDownloaded == true

    // Low space warning state
    var showLowSpaceWarning by remember { mutableStateOf(false) }
    val hasEnoughSpace = remember(modelStatus) { downloadManager.hasEnoughSpace(defaultModel.id) }

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

        // Step 1: Download AI Model (most important!)
        Text(
            text = "1. Download AI Model",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    isModelReady -> MaterialTheme.colorScheme.primaryContainer
                    isModelDownloaded -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                }
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Status indicator
                    Text(
                        text = when {
                            isModelReady -> "✓"
                            isModelDownloaded -> "◐"
                            else -> "!"
                        },
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isModelReady -> MaterialTheme.colorScheme.primary
                            isModelDownloaded -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.error
                        }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                isModelReady -> "Model Active"
                                isLoadingModel -> "Loading Model..."
                                isModelDownloaded -> "Model Downloaded"
                                downloadState is DownloadState.Downloading -> "Downloading..."
                                else -> "Model Required"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = when {
                                isModelReady -> "SPW expressions will be interpreted by AI"
                                isModelDownloaded -> "Tap to load the model"
                                else -> "${defaultModel.name} (${formatBytes(defaultModel.sizeBytes)})"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Download progress
                if (downloadState is DownloadState.Downloading) {
                    val state = downloadState as DownloadState.Downloading
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${formatBytes(state.bytesDownloaded)} / ${formatBytes(state.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Error message
                if (downloadState is DownloadState.Error) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = (downloadState as DownloadState.Error).message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // Low space warning
                if (showLowSpaceWarning && !isModelDownloaded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Low Storage Space",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "You may not have enough space for this download. Free up ${formatBytes(defaultModel.sizeBytes)} to continue.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { showLowSpaceWarning = false },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Cancel", fontSize = 12.sp)
                                }
                                Button(
                                    onClick = {
                                        showLowSpaceWarning = false
                                        downloadManager.startBackgroundDownload(defaultModel.id)
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Download Anyway", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                // Action button
                if (!isModelReady && downloadState !is DownloadState.Downloading && !showLowSpaceWarning) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (isModelDownloaded) {
                                modelStatus?.modelPath?.let { path ->
                                    scope.launch { InferenceManager.loadModel(path, settings.inferenceParams) }
                                }
                            } else if (!hasEnoughSpace) {
                                showLowSpaceWarning = true
                            } else {
                                downloadManager.startBackgroundDownload(defaultModel.id)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoadingModel
                    ) {
                        Text(
                            if (isModelDownloaded) {
                                if (isLoadingModel) "Loading..." else "Load Model"
                            } else {
                                "Download Model"
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Step 2: Enable Keyboard
        Text(
            text = "2. Enable Keyboard",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )

        SetupStep(
            number = 1,
            title = "Open System Settings",
            description = "Go to Languages & input → On-screen keyboard"
        )

        SetupStep(
            number = 2,
            title = "Enable spweebo'ard",
            description = "Toggle on spweebo'ard in the list"
        )

        SetupStep(
            number = 3,
            title = "Switch to spweebo'ard",
            description = "Use the keyboard icon to select it when typing"
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Settings")
            }

            Button(
                onClick = {
                    val imm = context.getSystemService(InputMethodManager::class.java)
                    imm.showInputMethodPicker()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Switch")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // Playground section
        SpwPlayground()
    }
}

/**
 * Interactive playground for testing SPW expressions.
 * Uses the actual keyboard layout for a realistic preview.
 */
@Composable
fun SpwPlayground() {
    val viewModel = remember { KeyboardViewModel() }
    var committedText by remember { mutableStateOf<String?>(null) }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Playground",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )

        Text(
            text = "Try the full keyboard experience below",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Show committed output
        if (committedText != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Sent:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = committedText!!,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }

        // Actual keyboard layout
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            KeyboardLayout(
                viewModel = viewModel,
                onCommit = { text ->
                    committedText = text
                    viewModel.commit()
                },
                onDelete = {
                    // Just pop from buffer in playground mode
                }
            )
        }

        // Symbol reference
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Symbol Reference",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "~ potential  # vibration  . ground  ? wonder  ! action",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "* value  & subject  @ perspective  ^ integration",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "<> concept  () scene  [] mode  {} direction",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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

/**
 * Represents a conceptual ground - persistent context for SPW interpretation.
 */
data class Ground(
    val id: String,
    val name: String,
    val spw: String,
    val description: String,
    val category: String
) {
    companion object {
        /**
         * Convert from Rust SpwGround to Kotlin Ground.
         */
        fun fromRust(rust: uniffi.spweeboard_core.SpwGround): Ground {
            return Ground(
                id = rust.id,
                name = rust.name,
                spw = rust.content,
                description = rust.description,
                category = rust.category
            )
        }
    }
}

/**
 * Preset grounds library - loaded from Rust core (single source of truth).
 */
val presetGrounds: List<Ground> by lazy {
    uniffi.spweeboard_core.presetGrounds().map { Ground.fromRust(it) }
}

@Composable
fun GroundsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedGround by remember { mutableStateOf<Ground?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    // Custom grounds from Rust store
    val groundStore = remember {
        try {
            val dbPath = context.filesDir.resolve("grounds.db").absolutePath
            SpwGroundStore.open(dbPath)
        } catch (e: Exception) {
            null
        }
    }
    var customGrounds by remember { mutableStateOf<List<SpwGround>>(emptyList()) }

    // Load custom grounds
    LaunchedEffect(groundStore) {
        groundStore?.let {
            customGrounds = it.list()
        }
    }

    // Dialog state
    var showEditorDialog by remember { mutableStateOf(false) }
    var editingGround by remember { mutableStateOf<SpwGround?>(null) }
    var deletingGround by remember { mutableStateOf<SpwGround?>(null) }

    val filteredGrounds = remember(searchQuery) {
        if (searchQuery.isEmpty()) {
            presetGrounds
        } else {
            presetGrounds.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.spw.contains(searchQuery) ||
                it.description.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val filteredCustomGrounds = remember(searchQuery, customGrounds) {
        if (searchQuery.isEmpty()) {
            customGrounds
        } else {
            customGrounds.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.content.contains(searchQuery)
            }
        }
    }

    val groupedGrounds = remember(filteredGrounds) {
        filteredGrounds.groupBy { it.category }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Active ground display
            if (selectedGround != null) {
                ActiveGroundCard(
                    ground = selectedGround!!,
                    onClear = { selectedGround = null }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Search field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search grounds...") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Ground list
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Custom grounds section (show first if any exist)
                if (filteredCustomGrounds.isNotEmpty()) {
                    Text(
                        text = "Custom",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    filteredCustomGrounds.forEach { customGround ->
                        CustomGroundCard(
                            ground = customGround,
                            onClick = {
                                // Convert to Ground for selection
                                selectedGround = Ground(
                                    id = customGround.id,
                                    name = customGround.name,
                                    spw = customGround.content,
                                    description = if (customGround.contentType == SpwGroundContentType.SPW) {
                                        "Custom SPW ground"
                                    } else {
                                        customGround.content
                                    },
                                    category = "Custom"
                                )
                            },
                            onEdit = {
                                editingGround = customGround
                                showEditorDialog = true
                            },
                            onDelete = {
                                deletingGround = customGround
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Preset grounds
                groupedGrounds.forEach { (category, grounds) ->
                    Text(
                        text = category,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    grounds.forEach { ground ->
                        GroundCard(
                            ground = ground,
                            isSelected = selectedGround?.id == ground.id,
                            onClick = { selectedGround = ground }
                        )
                    }
                }

                // Bottom padding for FAB
                Spacer(modifier = Modifier.height(72.dp))
            }
        }

        // FAB for creating new ground
        FloatingActionButton(
            onClick = {
                editingGround = null
                showEditorDialog = true
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ) {
            Text("+", fontSize = 24.sp)
        }
    }

    // Editor dialog
    if (showEditorDialog) {
        GroundEditorDialog(
            existingGround = editingGround,
            onDismiss = {
                showEditorDialog = false
                editingGround = null
            },
            onSave = { ground ->
                groundStore?.let { store ->
                    val result = store.save(ground)
                    if (result.success) {
                        customGrounds = store.list()
                    }
                }
                showEditorDialog = false
                editingGround = null
            }
        )
    }

    // Delete confirmation dialog
    if (deletingGround != null) {
        DeleteGroundDialog(
            groundName = deletingGround!!.name,
            onDismiss = { deletingGround = null },
            onConfirm = {
                groundStore?.let { store ->
                    store.delete(deletingGround!!.id)
                    customGrounds = store.list()
                }
                deletingGround = null
            }
        )
    }
}

@Composable
private fun CustomGroundCard(
    ground: SpwGround,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ground.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = ground.content,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = if (ground.contentType == SpwGroundContentType.SPW) {
                            FontFamily.Monospace
                        } else FontFamily.Default
                    ),
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                    maxLines = 2
                )
                Text(
                    text = if (ground.contentType == SpwGroundContentType.SPW) "SPW" else "Natural",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onEdit,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("Edit", fontSize = 12.sp)
                }
                TextButton(
                    onClick = onDelete,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ActiveGroundCard(
    ground: Ground,
    onClear: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Active Ground",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
                Text(
                    text = ground.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = ground.spw,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
            IconButton(onClick = onClear) {
                Text(
                    text = "×",
                    fontSize = 24.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun GroundCard(
    ground: Ground,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = ground.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                if (isSelected) {
                    Text(
                        text = "✓",
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = ground.spw,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = ground.description,
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloadManager = remember { ModelDownloadManager.getInstance(context) }
    val inferenceStatus by InferenceManager.status.collectAsState()
    val isLoadingModel by InferenceManager.isLoading.collectAsState()

    // Settings repository
    val settingsRepository = remember { SettingsRepository.getInstance(context) }
    val settings by settingsRepository.settings.collectAsState(
        initial = com.github.spwplace.spweeboard.settings.SpweeboardSettings()
    )

    var selectedModelId by remember { mutableStateOf(ModelDownloadManager.DEFAULT_MODEL.id) }
    var modelStatuses by remember { mutableStateOf(
        ModelDownloadManager.AVAILABLE_MODELS.associate { it.id to downloadManager.getModelStatus(it.id) }
    ) }

    // Observe WorkManager download state for selected model
    val downloadState by downloadManager.observeDownloadState(selectedModelId)
        .collectAsState(initial = DownloadState.Idle)

    // Refresh model statuses when download completes and auto-load model
    LaunchedEffect(downloadState) {
        if (downloadState is DownloadState.Completed) {
            modelStatuses = ModelDownloadManager.AVAILABLE_MODELS.associate {
                it.id to downloadManager.getModelStatus(it.id)
            }
            // Auto-load the downloaded model with current inference params
            val completedState = downloadState as DownloadState.Completed
            InferenceManager.loadModel(completedState.modelPath, settings.inferenceParams)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
            supportingContent = { Text("Vibrate when pressing keys") },
            trailingContent = {
                Switch(
                    checked = settings.hapticEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { settingsRepository.setHapticEnabled(enabled) }
                    }
                )
            }
        )

        ListItem(
            headlineContent = { Text("Streaming Preview") },
            supportingContent = { Text("Show interpretation as it generates") },
            trailingContent = {
                Switch(
                    checked = settings.streamingEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { settingsRepository.setStreamingEnabled(enabled) }
                    }
                )
            }
        )

        // Theme selection
        ListItem(
            headlineContent = { Text("Theme") },
            supportingContent = {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = {
                                scope.launch { settingsRepository.setThemeMode(mode) }
                            },
                            label = {
                                Text(
                                    text = when (mode) {
                                        ThemeMode.System -> "System"
                                        ThemeMode.Light -> "Light"
                                        ThemeMode.Dark -> "Dark"
                                    },
                                    fontSize = 12.sp
                                )
                            }
                        )
                    }
                }
            }
        )

        HorizontalDivider()

        Text(
            text = "Language Model",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )

        // Inference engine status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when (inferenceStatus) {
                    is InferenceStatus.Loaded -> MaterialTheme.colorScheme.primaryContainer
                    is InferenceStatus.Loading -> MaterialTheme.colorScheme.secondaryContainer
                    is InferenceStatus.Error -> MaterialTheme.colorScheme.errorContainer
                    is InferenceStatus.NotLoaded -> MaterialTheme.colorScheme.surfaceContainerLow
                }
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isLoadingModel) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (inferenceStatus) {
                            is InferenceStatus.Loaded -> "LLM Active"
                            is InferenceStatus.Loading -> "Loading Model..."
                            is InferenceStatus.Error -> "LLM Error"
                            is InferenceStatus.NotLoaded -> "No Model Loaded"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = when (inferenceStatus) {
                            is InferenceStatus.Loaded -> "SPW expressions interpreted by on-device AI"
                            is InferenceStatus.Loading -> "Please wait..."
                            is InferenceStatus.Error -> (inferenceStatus as InferenceStatus.Error).message
                            is InferenceStatus.NotLoaded -> "Using simple symbol mapping"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (inferenceStatus is InferenceStatus.Loaded) {
                    TextButton(onClick = { InferenceManager.unloadModel() }) {
                        Text("Unload")
                    }
                }
            }
        }

        Text(
            text = "Download a model for on-device SPW interpretation",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Model selection cards
        ModelDownloadManager.AVAILABLE_MODELS.forEach { model ->
            val status = modelStatuses[model.id]
            // Check if this model's file is the active one (use filename for exact match)
            val isActiveModel = (inferenceStatus as? InferenceStatus.Loaded)?.modelPath?.endsWith(model.filename) == true
            ModelCard(
                model = model,
                isDownloaded = status?.isDownloaded == true,
                isSelected = selectedModelId == model.id,
                isActive = isActiveModel,
                isLoading = isLoadingModel && selectedModelId == model.id,
                hasEnoughSpace = downloadManager.hasEnoughSpace(model.id),
                downloadState = if (selectedModelId == model.id) downloadState else DownloadState.Idle,
                onSelect = { selectedModelId = model.id },
                onDownload = {
                    downloadManager.startBackgroundDownload(model.id)
                },
                onLoad = {
                    status?.modelPath?.let { path ->
                        scope.launch {
                            InferenceManager.loadModel(path, settings.inferenceParams)
                        }
                    }
                },
                onDelete = {
                    // Unload model first if it's active
                    if (isActiveModel) {
                        InferenceManager.unloadModel()
                    }
                    downloadManager.deleteModel(model.id)
                    modelStatuses = ModelDownloadManager.AVAILABLE_MODELS.associate {
                        it.id to downloadManager.getModelStatus(it.id)
                    }
                },
                onCancelDownload = {
                    downloadManager.cancelDownload(model.id)
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        HorizontalDivider()

        // Inference Parameters Section
        Text(
            text = "Generation",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )

        // Temperature slider
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Temperature",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = String.format(Locale.US, "%.1f", settings.inferenceParams.temperature),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = "Lower = focused, higher = creative",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = settings.inferenceParams.temperature,
                onValueChange = { temp ->
                    scope.launch { settingsRepository.setTemperature(temp) }
                },
                valueRange = 0f..2f,
                steps = 19
            )
        }

        // Max tokens slider
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Max Tokens",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = settings.inferenceParams.maxTokens.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = "Maximum length of generated text",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = settings.inferenceParams.maxTokens.toFloat(),
                onValueChange = { tokens ->
                    scope.launch { settingsRepository.setMaxTokens(tokens.toInt()) }
                },
                valueRange = 32f..512f,
                steps = 14
            )
        }

        // Advanced parameters (collapsible)
        var showAdvanced by remember { mutableStateOf(false) }

        Surface(
            onClick = { showAdvanced = !showAdvanced },
            color = Color.Transparent
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Advanced Parameters",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = if (showAdvanced) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (showAdvanced) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        AnimatedVisibility(visible = showAdvanced) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Top-P
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Top-P", style = MaterialTheme.typography.bodySmall)
                        Text(
                            String.format(Locale.US, "%.2f", settings.inferenceParams.topP),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = settings.inferenceParams.topP,
                        onValueChange = { value ->
                            scope.launch {
                                settingsRepository.setInferenceParams(
                                    settings.inferenceParams.copy(topP = value)
                                )
                            }
                        },
                        valueRange = 0.1f..1f
                    )
                }

                // Top-K
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Top-K", style = MaterialTheme.typography.bodySmall)
                        Text(
                            settings.inferenceParams.topK.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = settings.inferenceParams.topK.toFloat(),
                        onValueChange = { value ->
                            scope.launch {
                                settingsRepository.setInferenceParams(
                                    settings.inferenceParams.copy(topK = value.toInt())
                                )
                            }
                        },
                        valueRange = 1f..100f,
                        steps = 98
                    )
                }

                // Repeat Penalty
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Repeat Penalty", style = MaterialTheme.typography.bodySmall)
                        Text(
                            String.format(Locale.US, "%.2f", settings.inferenceParams.repeatPenalty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = settings.inferenceParams.repeatPenalty,
                        onValueChange = { value ->
                            scope.launch {
                                settingsRepository.setInferenceParams(
                                    settings.inferenceParams.copy(repeatPenalty = value)
                                )
                            }
                        },
                        valueRange = 1f..2f
                    )
                }

                // Reset to defaults button
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            settingsRepository.setInferenceParams(
                                com.github.spwplace.spweeboard.settings.InferenceParams()
                            )
                        }
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Reset to Defaults", fontSize = 12.sp)
                }
            }
        }

        HorizontalDivider()

        // Storage Management Section
        Text(
            text = "Storage",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )

        val storageInfo = remember(modelStatuses) { downloadManager.getStorageInfo() }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Storage bar visualization
                val totalCapacity = storageInfo.totalUsed + storageInfo.availableSpace
                val modelsPercent = if (totalCapacity > 0) {
                    (storageInfo.modelsSize.toFloat() / totalCapacity).coerceIn(0f, 1f)
                } else 0f
                val partialPercent = if (totalCapacity > 0) {
                    (storageInfo.partialDownloadsSize.toFloat() / totalCapacity).coerceIn(0f, 1f)
                } else 0f

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                ) {
                    if (modelsPercent > 0) {
                        Surface(
                            modifier = Modifier
                                .weight(modelsPercent.coerceAtLeast(0.01f))
                                .fillMaxHeight(),
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)
                        ) {}
                    }
                    if (partialPercent > 0) {
                        Surface(
                            modifier = Modifier
                                .weight(partialPercent.coerceAtLeast(0.01f))
                                .fillMaxHeight(),
                            color = MaterialTheme.colorScheme.tertiary
                        ) {}
                    }
                    val availablePercent = 1f - modelsPercent - partialPercent
                    if (availablePercent > 0) {
                        Surface(
                            modifier = Modifier
                                .weight(availablePercent.coerceAtLeast(0.01f))
                                .fillMaxHeight(),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(
                                topStart = if (modelsPercent == 0f && partialPercent == 0f) 4.dp else 0.dp,
                                bottomStart = if (modelsPercent == 0f && partialPercent == 0f) 4.dp else 0.dp,
                                topEnd = 4.dp,
                                bottomEnd = 4.dp
                            )
                        ) {}
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Legend
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StorageLegendItem(
                        color = MaterialTheme.colorScheme.primary,
                        label = "Models",
                        size = storageInfo.modelsSize
                    )
                    if (storageInfo.partialDownloadsSize > 0) {
                        StorageLegendItem(
                            color = MaterialTheme.colorScheme.tertiary,
                            label = "Partial",
                            size = storageInfo.partialDownloadsSize
                        )
                    }
                    StorageLegendItem(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        label = "Available",
                        size = storageInfo.availableSpace
                    )
                }

                // Clear partial downloads button
                if (storageInfo.partialDownloadsSize > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            downloadManager.clearAllPartialDownloads()
                            // Refresh statuses
                            modelStatuses = ModelDownloadManager.AVAILABLE_MODELS.associate {
                                it.id to downloadManager.getModelStatus(it.id)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Clear Partial Downloads (${formatBytes(storageInfo.partialDownloadsSize)})")
                    }
                }
            }
        }

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

@Composable
private fun ModelCard(
    model: ModelInfo,
    isDownloaded: Boolean,
    isSelected: Boolean,
    isActive: Boolean,
    isLoading: Boolean,
    hasEnoughSpace: Boolean,
    downloadState: DownloadState,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
    onCancelDownload: () -> Unit = {}
) {
    val isDownloading = downloadState is DownloadState.Downloading || downloadState is DownloadState.Verifying
    var showLowSpaceWarning by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isDownloading && !isLoading) { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = when {
                isActive -> MaterialTheme.colorScheme.primaryContainer
                isSelected && isDownloaded -> MaterialTheme.colorScheme.secondaryContainer
                isSelected -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = model.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatBytes(model.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (isDownloaded) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isActive) {
                            Text(
                                text = "Active",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        IconButton(onClick = onDelete, enabled = !isLoading) {
                            Text("×", fontSize = 20.sp)
                        }
                    }
                }
            }

            // Download progress or load button
            when (downloadState) {
                is DownloadState.Downloading -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { downloadState.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${formatBytes(downloadState.bytesDownloaded)} / ${formatBytes(downloadState.totalBytes)} (${(downloadState.progress * 100).toInt()}%)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = onCancelDownload,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("Cancel", fontSize = 12.sp)
                        }
                    }
                }
                is DownloadState.Verifying -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Verifying download...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is DownloadState.Error -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = downloadState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onDownload) {
                        Text("Retry Download")
                    }
                }
                is DownloadState.Completed -> {
                    // Will refresh status via LaunchedEffect
                }
                is DownloadState.Idle -> {
                    if (isSelected) {
                        Spacer(modifier = Modifier.height(12.dp))

                        // Low space warning
                        if (showLowSpaceWarning && !isDownloaded) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "Low Storage Space",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        text = "You may not have enough space. Need ~${formatBytes(model.sizeBytes)}.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = { showLowSpaceWarning = false },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Cancel", fontSize = 12.sp)
                                        }
                                        Button(
                                            onClick = {
                                                showLowSpaceWarning = false
                                                onDownload()
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Download", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        } else if (isDownloaded && !isActive) {
                            Button(
                                onClick = onLoad,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isLoading
                            ) {
                                Text(if (isLoading) "Loading..." else "Load Model")
                            }
                        } else if (!isDownloaded) {
                            Button(
                                onClick = {
                                    if (!hasEnoughSpace) {
                                        showLowSpaceWarning = true
                                    } else {
                                        onDownload()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Download Model")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(Locale.US, "%.1f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}

@Composable
private fun StorageLegendItem(
    color: Color,
    label: String,
    size: Long
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Surface(
            modifier = Modifier.size(8.dp),
            color = color,
            shape = RoundedCornerShape(2.dp)
        ) {}
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatBytes(size),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

