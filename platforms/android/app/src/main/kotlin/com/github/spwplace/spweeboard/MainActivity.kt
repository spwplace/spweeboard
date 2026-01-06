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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import com.github.spwplace.spweeboard.keyboard.KeyboardLayout
import com.github.spwplace.spweeboard.keyboard.KeyboardViewModel
import com.github.spwplace.spweeboard.model.DownloadState
import com.github.spwplace.spweeboard.model.InferenceManager
import com.github.spwplace.spweeboard.model.InferenceStatus
import com.github.spwplace.spweeboard.model.ModelDownloadManager
import com.github.spwplace.spweeboard.model.ModelInfo
import com.github.spwplace.spweeboard.ui.theme.SpweeboardTheme
import kotlinx.coroutines.launch
import java.util.Locale

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
    val downloadManager = remember { ModelDownloadManager(context) }
    val downloadState by downloadManager.downloadState.collectAsState(initial = DownloadState.Idle)
    val inferenceStatus by InferenceManager.status.collectAsState()
    val isLoadingModel by InferenceManager.isLoading.collectAsState()

    // Track model status
    val defaultModel = ModelDownloadManager.DEFAULT_MODEL
    var modelStatus by remember { mutableStateOf(downloadManager.getModelStatus(defaultModel.id)) }

    // Refresh status when download completes
    LaunchedEffect(downloadState) {
        if (downloadState is DownloadState.Completed) {
            modelStatus = downloadManager.getModelStatus(defaultModel.id)
            // Auto-load the model
            val completedState = downloadState as DownloadState.Completed
            InferenceManager.loadModel(completedState.modelPath)
        }
    }

    // Determine setup completion states
    val isModelReady = inferenceStatus is InferenceStatus.Loaded
    val isModelDownloaded = modelStatus?.isDownloaded == true

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

                // Action button
                if (!isModelReady && downloadState !is DownloadState.Downloading) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (isModelDownloaded) {
                                modelStatus?.modelPath?.let { path ->
                                    scope.launch { InferenceManager.loadModel(path) }
                                }
                            } else {
                                scope.launch { downloadManager.downloadModel(defaultModel.id) }
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
)

/**
 * Preset grounds library.
 */
val presetGrounds = listOf(
    Ground(
        id = "software",
        name = "Software Development",
        spw = ".{software}",
        description = "Grounded toward software creation",
        category = "Work"
    ),
    Ground(
        id = "craft",
        name = "Craftsperson",
        spw = "@[craft].{utility}",
        description = "Perspective in craft mode, grounded in usefulness",
        category = "Work"
    ),
    Ground(
        id = "poetry",
        name = "Poetic Voice",
        spw = "@[poetry]~",
        description = "Poetry's becoming perspective",
        category = "Creative"
    ),
    Ground(
        id = "inquiry",
        name = "Deep Inquiry",
        spw = "?{&@.}",
        description = "Wondering about subject-perspective-ground flow",
        category = "Philosophical"
    ),
    Ground(
        id = "becoming",
        name = "Becoming",
        spw = "&~*^",
        description = "Subject becoming through value integration",
        category = "Philosophical"
    ),
    Ground(
        id = "resonance",
        name = "Resonance",
        spw = "#.&",
        description = "Vibration grounding the subject",
        category = "Creative"
    ),
    Ground(
        id = "action",
        name = "Action-Oriented",
        spw = "!{*}",
        description = "Asserting toward value",
        category = "Work"
    ),
    Ground(
        id = "reflection",
        name = "Reflective",
        spw = "@&.",
        description = "Perspective on subject's foundation",
        category = "Philosophical"
    )
)

@Composable
fun GroundsScreen(modifier: Modifier = Modifier) {
    var selectedGround by remember { mutableStateOf<Ground?>(null) }
    var searchQuery by remember { mutableStateOf("") }

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

    val groupedGrounds = remember(filteredGrounds) {
        filteredGrounds.groupBy { it.category }
    }

    Column(
        modifier = modifier
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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

            // Custom ground hint
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Custom Grounds",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Create your own SPW grounds in a future update",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
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
    val downloadManager = remember { ModelDownloadManager(context) }
    val downloadState by downloadManager.downloadState.collectAsState(initial = DownloadState.Idle)
    val inferenceStatus by InferenceManager.status.collectAsState()
    val isLoadingModel by InferenceManager.isLoading.collectAsState()

    var selectedModelId by remember { mutableStateOf(ModelDownloadManager.DEFAULT_MODEL.id) }
    var modelStatuses by remember { mutableStateOf(
        ModelDownloadManager.AVAILABLE_MODELS.associate { it.id to downloadManager.getModelStatus(it.id) }
    ) }

    // Refresh model statuses when download completes and auto-load model
    LaunchedEffect(downloadState) {
        if (downloadState is DownloadState.Completed) {
            modelStatuses = ModelDownloadManager.AVAILABLE_MODELS.associate {
                it.id to downloadManager.getModelStatus(it.id)
            }
            // Auto-load the downloaded model
            val completedState = downloadState as DownloadState.Completed
            InferenceManager.loadModel(completedState.modelPath)
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
            val isActiveModel = (inferenceStatus as? InferenceStatus.Loaded)?.modelPath?.contains(model.id) == true
            ModelCard(
                model = model,
                isDownloaded = status?.isDownloaded == true,
                isSelected = selectedModelId == model.id,
                isActive = isActiveModel,
                isLoading = isLoadingModel && selectedModelId == model.id,
                downloadState = if (selectedModelId == model.id) downloadState else DownloadState.Idle,
                onSelect = { selectedModelId = model.id },
                onDownload = {
                    scope.launch {
                        downloadManager.downloadModel(model.id)
                    }
                },
                onLoad = {
                    status?.modelPath?.let { path ->
                        scope.launch {
                            InferenceManager.loadModel(path)
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
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
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

        val totalSize = remember(modelStatuses) { downloadManager.getTotalModelsSize() }
        if (totalSize > 0) {
            ListItem(
                headlineContent = { Text("Models Storage") },
                supportingContent = { Text(formatBytes(totalSize)) }
            )
        }
    }
}

@Composable
private fun ModelCard(
    model: ModelInfo,
    isDownloaded: Boolean,
    isSelected: Boolean,
    isActive: Boolean,
    isLoading: Boolean,
    downloadState: DownloadState,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onLoad: () -> Unit,
    onDelete: () -> Unit
) {
    val isDownloading = downloadState is DownloadState.Downloading || downloadState is DownloadState.Verifying

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
                    Text(
                        text = "${formatBytes(downloadState.bytesDownloaded)} / ${formatBytes(downloadState.totalBytes)} (${(downloadState.progress * 100).toInt()}%)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                        if (isDownloaded && !isActive) {
                            Button(
                                onClick = onLoad,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isLoading
                            ) {
                                Text(if (isLoading) "Loading..." else "Load Model")
                            }
                        } else if (!isDownloaded) {
                            Button(
                                onClick = onDownload,
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

