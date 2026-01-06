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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import android.content.ClipData
import android.content.ClipboardManager
import com.github.spwplace.spweeboard.keyboard.KeyboardLayout
import com.github.spwplace.spweeboard.keyboard.KeyboardViewModel
import com.github.spwplace.spweeboard.keyboard.GroundOption
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
    var selectedTab by remember { mutableIntStateOf(if (startOnSettingsTab) 3 else 0) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("spweebo'ard") }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Outlined.Create, contentDescription = "Compose") },
                    label = { Text("Compose") },
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
                    icon = { Icon(Icons.Outlined.Info, contentDescription = "Setup") },
                    label = { Text("Setup") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Outlined.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 }
                )
            }
        }
    ) { padding ->
        // Keep all tabs always composed to avoid state reinitialization flashes
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            PlaygroundScreen(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (selectedTab == 0) 1f else 0f)
                    .zIndex(if (selectedTab == 0) 1f else 0f),
                onNavigateToSettings = { selectedTab = 3 }
            )
            GroundsScreen(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (selectedTab == 1) 1f else 0f)
                    .zIndex(if (selectedTab == 1) 1f else 0f)
            )
            SetupScreen(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (selectedTab == 2) 1f else 0f)
                    .zIndex(if (selectedTab == 2) 1f else 0f)
            )
            SettingsScreen(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (selectedTab == 3) 1f else 0f)
                    .zIndex(if (selectedTab == 3) 1f else 0f)
            )
        }
    }
}

/**
 * Entry in the playground conversation history.
 */
data class PlaygroundEntry(
    val spwInput: String,
    val interpretation: String,
    val groundName: String?,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Main playground screen - the primary way to use spweebo'ard.
 * Shows a conversation-style history of SPW expressions and their interpretations.
 */
@Composable
fun PlaygroundScreen(
    modifier: Modifier = Modifier,
    onNavigateToSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel = remember { KeyboardViewModel() }
    val inferenceStatus by InferenceManager.status.collectAsState()

    // Conversation history
    var history by remember { mutableStateOf<List<PlaygroundEntry>>(emptyList()) }

    // Symbol reference visibility
    var showSymbolReference by remember { mutableStateOf(false) }

    // Track current SPW input for history
    val currentBuffer by viewModel.buffer
    val currentGround by viewModel.selectedGround

    Column(
        modifier = modifier
            .fillMaxSize()
    ) {
        // Status bar - show loading state or prompt to set up
        when (inferenceStatus) {
            is InferenceStatus.Loading -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Loading model...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            is InferenceStatus.NotLoaded, is InferenceStatus.Error -> {
                Surface(
                    onClick = onNavigateToSettings,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (inferenceStatus is InferenceStatus.Error)
                                "Model error" else "No model loaded",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "Go to Settings →",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
            is InferenceStatus.Loaded -> {
                // No banner needed when model is loaded
            }
        }

        // Conversation history area (scrollable)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (history.isEmpty()) {
                // Empty state
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "~",
                        fontSize = 48.sp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Compose with symbols",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Type SPW expressions below and press send to interpret",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )

                    // Quick symbol hint
                    Spacer(modifier = Modifier.height(24.dp))
                    Surface(
                        onClick = { showSymbolReference = !showSymbolReference },
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Tap for symbol guide",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            } else {
                // Conversation history
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Clear history button at top
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${history.size} expression${if (history.size != 1) "s" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { history = emptyList() }) {
                            Text("Clear", fontSize = 12.sp)
                        }
                    }

                    history.forEach { entry ->
                        PlaygroundEntryCard(
                            entry = entry,
                            onCopy = {
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(
                                    ClipData.newPlainText("Interpretation", entry.interpretation)
                                )
                            }
                        )
                    }

                    // Spacer for keyboard
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        // Symbol reference (collapsible)
        AnimatedVisibility(visible = showSymbolReference) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Symbol Reference",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(
                            onClick = { showSymbolReference = false },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Text("×", fontSize = 16.sp)
                        }
                    }
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

        // Keyboard
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            KeyboardLayout(
                viewModel = viewModel,
                onCommit = { interpretation ->
                    // Add to history
                    history = history + PlaygroundEntry(
                        spwInput = currentBuffer,
                        interpretation = interpretation,
                        groundName = if (currentGround.id != "none") currentGround.name else null
                    )
                    viewModel.commit()
                },
                onDelete = {
                    // Just pop from buffer in playground mode
                },
                onOpenSettings = {
                    // Could navigate to settings tab
                }
            )
        }
    }
}

/**
 * Card displaying a single playground entry (SPW + interpretation).
 */
@Composable
private fun PlaygroundEntryCard(
    entry: PlaygroundEntry,
    onCopy: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // SPW input row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // Ground badge if present
                    if (entry.groundName != null) {
                        Text(
                            text = entry.groundName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(bottom = 4.dp)
                        )
                    }
                    // SPW expression
                    Text(
                        text = entry.spwInput,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(8.dp))

            // Interpretation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = entry.interpretation,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(32.dp)
                ) {
                    Text(
                        text = "📋",
                        fontSize = 14.sp
                    )
                }
            }
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

    // Initialize selectedModelId to the currently loaded model (if any), otherwise default
    val initialSelectedModel = remember {
        val loadedPath = (InferenceManager.status.value as? InferenceStatus.Loaded)?.modelPath
        if (loadedPath != null) {
            ModelDownloadManager.AVAILABLE_MODELS.find { loadedPath.endsWith(it.filename) }?.id
                ?: ModelDownloadManager.DEFAULT_MODEL.id
        } else {
            ModelDownloadManager.DEFAULT_MODEL.id
        }
    }
    var selectedModelId by remember { mutableStateOf(initialSelectedModel) }
    var modelStatuses by remember { mutableStateOf(
        ModelDownloadManager.AVAILABLE_MODELS.associate { it.id to downloadManager.getModelStatus(it.id) }
    ) }

    // Track download states for all models
    val downloadStates = ModelDownloadManager.AVAILABLE_MODELS.associate { model ->
        model.id to downloadManager.observeDownloadState(model.id)
            .collectAsState(initial = DownloadState.Idle)
    }

    // Refresh model statuses when any download completes
    downloadStates.forEach { (modelId, stateHolder) ->
        val state = stateHolder.value
        LaunchedEffect(state) {
            if (state is DownloadState.Completed) {
                modelStatuses = ModelDownloadManager.AVAILABLE_MODELS.associate {
                    it.id to downloadManager.getModelStatus(it.id)
                }
                // Auto-load if this was the selected model and file exists
                if (modelId == selectedModelId) {
                    val currentStatus = downloadManager.getModelStatus(modelId)
                    if (currentStatus.isDownloaded) {
                        InferenceManager.loadModel(state.modelPath, settings.inferenceParams)
                    }
                }
            }
        }
    }

    // Determine setup completion states
    val isModelReady = inferenceStatus is InferenceStatus.Loaded

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

        // Step 1: Enable Keyboard
        Text(
            text = "1. Enable Keyboard",
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

        Spacer(modifier = Modifier.height(16.dp))

        // Step 2: Download AI Model
        Text(
            text = "2. Download AI Model",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )

        // Inference engine status card
        val inferenceCardColor by animateColorAsState(
            targetValue = when (inferenceStatus) {
                is InferenceStatus.Loaded -> MaterialTheme.colorScheme.primaryContainer
                is InferenceStatus.Loading -> MaterialTheme.colorScheme.secondaryContainer
                is InferenceStatus.Error -> MaterialTheme.colorScheme.errorContainer
                is InferenceStatus.NotLoaded -> MaterialTheme.colorScheme.surfaceContainerLow
            },
            animationSpec = tween(300),
            label = "inferenceCardColor"
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = inferenceCardColor)
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
                            is InferenceStatus.NotLoaded -> "Select and download a model below"
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

        // Model selection cards
        ModelDownloadManager.AVAILABLE_MODELS.forEach { model ->
            val status = modelStatuses[model.id]
            val isDownloaded = status?.isDownloaded == true
            val isActiveModel = (inferenceStatus as? InferenceStatus.Loaded)?.modelPath?.endsWith(model.filename) == true
            val modelDownloadState = downloadStates[model.id]?.value ?: DownloadState.Idle
            key(model.id, isDownloaded, modelDownloadState) {
                ModelCard(
                    model = model,
                    isDownloaded = isDownloaded,
                    isSelected = selectedModelId == model.id,
                    isActive = isActiveModel,
                    isLoading = isLoadingModel && selectedModelId == model.id,
                    hasEnoughSpace = downloadManager.hasEnoughSpace(model.id),
                    downloadState = modelDownloadState,
                    onSelect = { selectedModelId = model.id },
                    onDownload = {
                        downloadManager.startBackgroundDownload(model.id)
                    },
                    onLoad = {
                        val currentStatus = downloadManager.getModelStatus(model.id)
                        currentStatus.modelPath?.let { path ->
                            scope.launch {
                                InferenceManager.loadModel(path, settings.inferenceParams)
                            }
                        }
                    },
                    onDelete = {
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
            }
            Spacer(modifier = Modifier.height(4.dp))
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
 * Preset grounds library - loaded from Rust core (single source of truth).
 * Uses SpwGround directly to preserve content_type information.
 */
val presetGrounds: List<SpwGround> by lazy {
    uniffi.spweeboard_core.presetGrounds()
}

@Composable
fun GroundsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedGround by remember { mutableStateOf<SpwGround?>(null) }
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

    // Filter presets - now using SpwGround directly
    val filteredPresets = remember(searchQuery) {
        if (searchQuery.isEmpty()) {
            presetGrounds
        } else {
            presetGrounds.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.content.contains(searchQuery) ||
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
                it.content.contains(searchQuery) ||
                it.description.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val groupedPresets = remember(filteredPresets) {
        filteredPresets.groupBy { it.category }
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

                    filteredCustomGrounds.forEach { ground ->
                        GroundCard(
                            ground = ground,
                            isSelected = selectedGround?.id == ground.id,
                            onClick = { selectedGround = ground },
                            onEdit = {
                                editingGround = ground
                                showEditorDialog = true
                            },
                            onDelete = {
                                deletingGround = ground
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Preset grounds
                groupedPresets.forEach { (category, grounds) ->
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
private fun ActiveGroundCard(
    ground: SpwGround,
    onClear: () -> Unit
) {
    val isSpw = ground.contentType == SpwGroundContentType.SPW
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Active Ground",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = if (isSpw) "SPW" else "Natural",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    )
                }
                Text(
                    text = ground.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = ground.content,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = if (isSpw) FontFamily.Monospace else FontFamily.Default
                    ),
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    maxLines = 2
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
    ground: SpwGround,
    isSelected: Boolean,
    onClick: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    val isSpw = ground.contentType == SpwGroundContentType.SPW
    val isCustom = onEdit != null || onDelete != null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else if (isCustom) {
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
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
                    // Content type badge
                    Text(
                        text = if (isSpw) "SPW" else "Natural",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (isSelected) {
                        Text(
                            text = "✓",
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    if (onEdit != null) {
                        TextButton(
                            onClick = onEdit,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Edit", fontSize = 12.sp)
                        }
                    }
                    if (onDelete != null) {
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
            Spacer(modifier = Modifier.height(4.dp))
            // Content - use monospace for SPW
            Text(
                text = ground.content,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = if (isSpw) FontFamily.Monospace else FontFamily.Default
                ),
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                } else {
                    MaterialTheme.colorScheme.primary
                },
                maxLines = 2
            )
            // Description if present
            if (ground.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = ground.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloadManager = remember { ModelDownloadManager.getInstance(context) }

    // Settings repository
    val settingsRepository = remember { SettingsRepository.getInstance(context) }
    val settings by settingsRepository.settings.collectAsState(
        initial = com.github.spwplace.spweeboard.settings.SpweeboardSettings()
    )

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

        var storageRefreshKey by remember { mutableIntStateOf(0) }
        val storageInfo = remember(storageRefreshKey) { downloadManager.getStorageInfo() }

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
                            storageRefreshKey++
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

    val cardColor by animateColorAsState(
        targetValue = when {
            isActive -> MaterialTheme.colorScheme.primaryContainer
            isSelected && isDownloaded -> MaterialTheme.colorScheme.secondaryContainer
            isSelected -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = tween(300),
        label = "modelCardColor"
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isDownloading && !isLoading) { onSelect() },
        colors = CardDefaults.cardColors(containerColor = cardColor)
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
                    // WorkManager says completed, but file might have been deleted
                    // Show download button if file no longer exists
                    if (isSelected && !isDownloaded) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onDownload,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Download Model")
                        }
                    }
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

