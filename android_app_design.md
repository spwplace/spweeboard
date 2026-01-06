# spweebo'ard Android App Design Specification

This document describes the complete specification of the spweebo'ard Android app to guide the iOS port.

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Core Components](#core-components)
4. [UI Structure](#ui-structure)
5. [Data Models](#data-models)
6. [Keyboard Service](#keyboard-service)
7. [Inference System](#inference-system)
8. [Model Management](#model-management)
9. [Settings & Persistence](#settings--persistence)
10. [Feature Flags](#feature-flags)
11. [Theming](#theming)
12. [iOS Implementation Notes](#ios-implementation-notes)

---

## Overview

**spweebo'ard** is a symbolic keyboard that allows users to compose thoughts using SPW (Symbolic Primitive Writing) cognitive primitives, which are then interpreted by an on-device LLM into natural language.

### Key Features

- **SPW Symbol Input**: Custom keyboard with SPW symbols (~, #, ., ?, !, *, &, @, ^) and bracket pairs (<>, (), [], {})
- **On-Device LLM Interpretation**: GGUF models run locally via llama.cpp (Rust bindings)
- **Grounds**: Context presets that modify interpretation (e.g., "Work Mode", "Creative Writing")
- **Streaming Output**: Real-time display of LLM generation
- **Expression History**: Recall previously typed SPW expressions
- **Custom Grounds**: User-created context definitions

### App Package

- **Package ID**: `com.github.spwplace.spweeboard`
- **App Name**: spweebo'ard

---

## Architecture

### Layer Overview

```
┌─────────────────────────────────────────────────┐
│                    UI Layer                      │
│  (Compose UI - MainActivity, KeyboardLayout)     │
├─────────────────────────────────────────────────┤
│                ViewModel Layer                   │
│         (KeyboardViewModel, state flows)         │
├─────────────────────────────────────────────────┤
│                 Manager Layer                    │
│  (InferenceManager, ModelDownloadManager,        │
│   SettingsRepository)                            │
├─────────────────────────────────────────────────┤
│              Rust Core (via UniFFI)              │
│  (SpwInferenceEngine, SpwGroundStore, parsing)   │
└─────────────────────────────────────────────────┘
```

### Native Library

The app loads a native Rust library (`libspweeboard_core.so`) via JNI/UniFFI that provides:

- SPW expression validation (`validateSpw`)
- Symbol/bracket definitions (`getSymbols`, `getBrackets`)
- Preset grounds (`presetGrounds`)
- Model information (`availableModels`, `defaultModelId`)
- Inference engine (`SpwInferenceEngine`)
- Ground storage (`SpwGroundStore`)
- Expression history (via `SpwGroundStore`)

---

## Core Components

### 1. SpweeboardApplication

**Purpose**: Application entry point, singleton initialization

**Responsibilities**:
- Load native library (`System.loadLibrary("spweeboard_core")`)
- Initialize `InferenceManager`
- Provide shared `SpwGroundStore` instance (thread-safe singleton)

**iOS Equivalent**: AppDelegate or App struct initialization

### 2. MainActivity

**Purpose**: Container app with setup instructions and ground management

**Tabs**:
1. **Compose** - Playground to test SPW expressions
2. **Grounds** - Browse and manage context presets
3. **Setup** - Model download and keyboard enable instructions
4. **Settings** - App configuration

**iOS Equivalent**: UITabBarController or SwiftUI TabView

### 3. SpweeboardService

**Purpose**: InputMethodService that renders the keyboard

**Lifecycle**:
- `onCreate()`: Initialize lifecycle, coroutine scope, recomposer, settings
- `onCreateInputView()`: Return Compose view with keyboard layout
- `onStartInputView()`: Resume lifecycle
- `onFinishInputView()`: Cancel in-progress interpretations
- `onDestroy()`: Clean up resources

**iOS Equivalent**: Custom Keyboard Extension (UIInputViewController)

---

## UI Structure

### Main App Screens

#### PlaygroundScreen (Compose Tab)

- Status banner showing model state (Loading/Error/Loaded)
- Conversation history of SPW expressions and their interpretations
- Symbol reference guide (collapsible)
- Embedded keyboard for testing

#### GroundsScreen

- Search field to filter grounds
- Active ground display card
- Custom grounds section (user-created)
- Preset grounds grouped by category
- FAB to create new ground
- Ground editor dialog
- Delete confirmation dialog

#### SetupScreen

- Model download card with:
  - Status indicator (!, ◐, ✓)
  - Download progress bar
  - Low space warning
  - Download/Load buttons
- Keyboard enable instructions (3 steps)
- Buttons to open system settings and keyboard picker

#### SettingsScreen

- **Keyboard Section**:
  - Haptic feedback toggle
  - Streaming preview toggle
  - Theme selection (System/Light/Dark)
- **Language Model Section**:
  - LLM status card
  - Model selection cards (multiple models available)
  - Download/Load/Delete actions per model
- **Generation Section**:
  - Temperature slider (0-2, default 0.7)
  - Max tokens slider (32-512, default 128)
  - Advanced parameters (collapsible):
    - Top-P (0.1-1, default 0.9)
    - Top-K (1-100, default 40)
    - Repeat Penalty (1-2, default 1.1)
  - Reset to defaults button
- **Storage Section**:
  - Visual storage bar
  - Models/Partial/Available breakdown
  - Clear partial downloads button
- **About Section**:
  - Version info

### Keyboard Layout

#### BufferDisplay

- Shows current SPW expression
- Parse state indicator (dot color):
  - Gray: Empty
  - Orange: Valid but not interpreted
  - Green: Valid with interpretation
  - Red: Invalid or error
- Loading state with shimmer animation
- Streaming text preview
- Error messages (tappable to open settings)
- Clear button
- Cancel button (if interpretation in progress)

#### GroundSelectorRow

- Horizontal scrolling chip list
- "None" option first
- Preset grounds from categories
- Custom grounds
- Visual selection state

#### HistoryButton

- Shows count badge when history exists
- Opens history sheet overlay

#### SpwSymbolRows

Two rows of SPW symbols loaded from Rust core:
- Row 1: ~ # . ? !
- Row 2: * & @ ^

One row of bracket pairs:
- <> () [] {}

Each bracket key is split into open/close halves.

#### QwertyLayout

Standard QWERTY layout with:
- Letter rows with appropriate spacing
- Bottom row: Backspace (⌫) | Space bar | Send (↑)
- Send button animated/colored based on `canSend` state

#### HistorySheet

- Slide-up overlay (60% height)
- Header with "Clear" and close buttons
- Lazy list of history items
- Tap to recall expression
- Empty state message

---

## Data Models

### SpwGround (from Rust)

```kotlin
data class SpwGround(
    val id: String,           // UUID string
    val name: String,         // Display name
    val contentType: SpwGroundContentType,  // SPW or NATURAL
    val content: String,      // The ground content
    val description: String,  // Optional description
    val category: String      // Grouping category
)

enum class SpwGroundContentType {
    SPW,     // SPW expression
    NATURAL  // Natural language description
}
```

### GroundOption (Kotlin wrapper)

```kotlin
data class GroundOption(
    val id: String,
    val name: String,
    val spw: String  // The content
)
```

### ParseState

```kotlin
enum class ParseState {
    Empty,   // No input
    Valid,   // Valid SPW expression
    Invalid  // Parse error
}
```

### LoadingState

```kotlin
sealed class LoadingState {
    object Idle : LoadingState()
    object CheckingModel : LoadingState()
    object Interpreting : LoadingState()

    val isLoading: Boolean get() = this !is Idle
}
```

### InferenceStatus

```kotlin
sealed class InferenceStatus {
    object NotLoaded : InferenceStatus()
    object Loading : InferenceStatus()
    data class Loaded(val modelPath: String) : InferenceStatus()
    data class Error(val message: String) : InferenceStatus()
}
```

### DownloadState

```kotlin
sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(
        val progress: Float,         // 0.0-1.0
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : DownloadState()
    object Verifying : DownloadState()
    data class Completed(val modelPath: String) : DownloadState()
    data class Error(val message: String) : DownloadState()
}
```

### ModelInfo

```kotlin
data class ModelInfo(
    val id: String,
    val name: String,
    val filename: String,
    val url: String,
    val sizeBytes: Long,
    val description: String
)
```

### StreamingResult

```kotlin
sealed class StreamingResult {
    object Thinking : StreamingResult()
    data class Chunk(val text: String) : StreamingResult()
    data class Complete(val fullText: String) : StreamingResult()
    data class Error(val message: String) : StreamingResult()
}
```

### PlaygroundEntry

```kotlin
data class PlaygroundEntry(
    val spwInput: String,
    val interpretation: String,
    val groundName: String?,
    val timestamp: Long = System.currentTimeMillis()
)
```

---

## Keyboard Service

### KeyboardViewModel

**State Properties** (all observable):
- `buffer: String` - Current input text
- `parseState: ParseState` - Validation state
- `interpretation: String?` - Last interpretation result
- `interpretError: String?` - Error message
- `loadingState: LoadingState` - Current loading state
- `selectedGround: GroundOption` - Active ground context
- `streamingText: String?` - Partial streaming result
- `isThinking: Boolean` - Model is generating but no output yet
- `history: List<String>` - Expression history (from Rust store)
- `isHistoryVisible: Boolean` - History sheet visibility
- `availableGrounds: List<GroundOption>` - All grounds (preset + custom)

**Actions**:
- `pushSymbol(symbol: String)` - Append SPW symbol
- `pushChar(char: String)` - Append character
- `pop()` - Remove last character
- `clear()` - Reset all state
- `send(onCommit: (String) -> Unit)` - Start interpretation, callback on complete
- `commit()` - Save to history and clear
- `recall(expression: String)` - Load from history
- `toggleHistory()` / `hideHistory()` / `clearHistory()`
- `selectGround(ground: GroundOption)` - Change active ground
- `initializeGround(groundId: String?)` - Load persisted ground
- `cancelInterpretation()` - Abort in-progress interpretation
- `dispose()` - Cleanup

**Private Methods**:
- `updateParseState()` - Validate via Rust `validateSpw()`
- `interpretAsync(input, ground)` - Start streaming interpretation

### Input Flow

1. User taps symbol/key -> `pushSymbol()`/`pushChar()`
2. `updateParseState()` validates via Rust FFI
3. If valid, UI shows orange dot; if invalid, red dot
4. User taps Send (↑):
   - `send()` called with commit callback
   - `interpretAsync()` starts streaming
   - `StreamingResult` events update UI
   - On `Complete`, callback invoked with text
5. `commit()` saves to history, commits text to input field, clears state

### Text Commitment (IME)

```kotlin
currentInputConnection?.commitText(text, 1)
```

**iOS Equivalent**: `textDocumentProxy.insertText(text)`

---

## Inference System

### InferenceManager (Singleton)

**Properties**:
- `status: StateFlow<InferenceStatus>` - Engine state
- `isLoading: StateFlow<Boolean>` - Model loading flag
- `streamingResults: SharedFlow<StreamingResult>` - Streaming output

**Methods**:
- `initialize()` - Create `SpwInferenceEngine` instance
- `loadModel(path, params)` - Load GGUF model with config
- `unloadModel()` - Release model
- `cancelInterpretation()` - Abort generation
- `interpret(input, groundName)` - Synchronous interpretation (unused)
- `interpretStreaming(input, groundName)` - Async streaming interpretation
- `isModelLoaded()` - Check status
- `getEngineStatus()` - Get Rust engine status

### SpwInferenceConfig (Rust)

```kotlin
data class SpwInferenceConfig(
    val modelPath: String,
    val nThreads: UInt = 4u,
    val nCtx: UInt = 2048u,
    val useGpu: Boolean = true,
    val nGpuLayers: UInt = 99u,
    val maxTokens: UInt,
    val temperature: Float,
    val topP: Float,
    val topK: UInt,
    val repeatPenalty: Float
)
```

### Streaming Callback

```kotlin
interface SpwStreamCallback {
    fun onChunk(phase: SpwStreamingPhase, text: String, isFinal: Boolean)
    fun onError(message: String)
}

enum class SpwStreamingPhase {
    THINKING,  // Model thinking (hidden)
    CONTENT    // Visible output
}
```

---

## Model Management

### ModelDownloadManager (Singleton)

**Static Properties**:
- `AVAILABLE_MODELS: List<ModelInfo>` - From Rust `availableModels()`
- `DEFAULT_MODEL: ModelInfo` - From Rust `defaultModelId()`

**Instance Properties**:
- `downloadState: Flow<DownloadState>` - Current download state

**Methods**:
- `startBackgroundDownload(modelId)` - Enqueue WorkManager job
- `observeDownloadState(modelId)` - Flow of download updates
- `cancelDownload(modelId)` - Cancel work
- `isDownloadRunning(modelId)` - Check if downloading
- `getModelStatus(modelId)` - Check if downloaded
- `getModelPath(modelId)` - Get file path
- `downloadModel(modelId)` - Direct download (with resume)
- `deleteModel(modelId)` - Remove model file
- `getPartialDownloadProgress(modelId)` - Resume progress
- `clearPartialDownload(modelId)` - Remove temp file
- `getTotalModelsSize()` / `getPartialDownloadsSize()` / `getAvailableStorage()`
- `hasEnoughSpace(modelId)` - Check before download
- `getStorageInfo()` - Full breakdown
- `clearAllPartialDownloads()` - Cleanup

### ModelDownloadWorker

WorkManager CoroutineWorker for background downloads:

- Shows foreground notification with progress
- Supports HTTP Range header for resume
- Survives app closure
- Reports progress via WorkInfo
- Verifies download size

**iOS Equivalent**: URLSession background download task with delegate

---

## Settings & Persistence

### SettingsRepository

Uses Jetpack DataStore (Preferences) for persistence.

### SpweeboardSettings

```kotlin
data class SpweeboardSettings(
    val hapticEnabled: Boolean = true,
    val streamingEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.System,
    val defaultGroundId: String? = null,
    val lastUsedModelPath: String? = null,
    val inferenceParams: InferenceParams = InferenceParams()
)
```

### InferenceParams

```kotlin
data class InferenceParams(
    val temperature: Float = 0.7f,
    val maxTokens: Int = 128,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f
)
```

### ThemeMode

```kotlin
enum class ThemeMode {
    System,
    Light,
    Dark
}
```

### Persistence Keys

| Key | Type | Default |
|-----|------|---------|
| `haptic_enabled` | Boolean | true |
| `streaming_enabled` | Boolean | true |
| `theme_mode` | String | "System" |
| `default_ground_id` | String | null |
| `last_used_model_path` | String | null |
| `inference_temperature` | Float | 0.7 |
| `inference_max_tokens` | Int | 128 |
| `inference_top_p` | Float | 0.9 |
| `inference_top_k` | Int | 40 |
| `inference_repeat_penalty` | Float | 1.1 |

**iOS Equivalent**: UserDefaults or SwiftData

---

## Feature Flags

### FeatureFlags Object

Compile-time flags backed by BuildConfig:

| Flag | Description | Default |
|------|-------------|---------|
| `streamingEnabled` | Show streaming output | BuildConfig |
| `historyEnabled` | Enable expression history | BuildConfig |
| `customGroundsEnabled` | Allow custom grounds | BuildConfig |
| `cancelInterpretationEnabled` | Show cancel button | BuildConfig |

**Constants**:
- `MAX_HISTORY_SIZE = 100`

**iOS Equivalent**: Build configuration flags or UserDefaults

---

## Theming

### SpweeboardTheme

- Supports System/Light/Dark modes
- Uses Material You dynamic colors on Android 12+
- Falls back to custom color schemes

### Color Schemes

**Light Theme Key Colors**:
- Primary: #4D55A9
- PrimaryContainer: #DEE0FF
- Secondary: #5C5D72
- Tertiary: #78536A
- Background: #FEFBFF

**Dark Theme Key Colors**:
- Primary: #BBC3FF
- PrimaryContainer: #353D90
- Secondary: #C5C4DD
- Tertiary: #E8B9D4
- Background: #1B1B1F

**iOS Equivalent**: SwiftUI Environment colorScheme with custom Color extensions

---

## iOS Implementation Notes

### Keyboard Extension

1. **Info.plist Configuration**:
   - `NSExtension` with `NSExtensionPointIdentifier: com.apple.keyboard-service`
   - `RequestsOpenAccess: YES` (needed for network access to load models)

2. **Shared Container**:
   - Use App Groups for sharing data between main app and keyboard extension
   - Models directory in shared container
   - Settings in shared UserDefaults suite

3. **Memory Constraints**:
   - iOS keyboard extensions have strict memory limits (~50MB)
   - May need to lazy-load model or use smaller quantization
   - Consider offloading model to main app process

4. **Network Access**:
   - Requires "Allow Full Access" from user
   - Show onboarding explaining why this is needed

### Native Library

1. **Build**: Compile Rust library as `.xcframework` for iOS
2. **UniFFI**: Generate Swift bindings from UDL
3. **Metal**: llama.cpp supports Metal for GPU acceleration on iOS

### UI Framework

- Use SwiftUI for declarative UI
- UIInputViewController for keyboard extension
- Combine or async/await for reactive state

### Haptic Feedback

```swift
let generator = UIImpactFeedbackGenerator(style: .light)
generator.impactOccurred()
```

### Background Downloads

Use `URLSession` with background configuration:

```swift
let config = URLSessionConfiguration.background(withIdentifier: "modelDownload")
let session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
```

### Notifications

1. Create notification content
2. Request authorization
3. Use `UNUserNotificationCenter` for local notifications

### Storage Paths

```swift
// Main app: Documents directory
let documentsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!

// Shared with extension: App Group container
let sharedDir = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.com.github.spwplace.spweeboard")!
```

---

## Summary of Key Behaviors

1. **First Launch Flow**:
   - Show Setup tab
   - User downloads model (with progress)
   - User enables keyboard in Settings
   - User switches to spweebo'ard

2. **Normal Usage Flow**:
   - User types SPW expression
   - Real-time validation shows parse state
   - User selects ground (optional)
   - User taps Send
   - Streaming interpretation appears
   - Text committed to input field

3. **Model Management**:
   - Multiple models available
   - Download with resume support
   - Storage management
   - Load/unload on demand

4. **Ground System**:
   - Preset grounds from categories
   - Custom grounds (SPW or Natural)
   - Persisted selection
   - Affects interpretation context

5. **History System**:
   - Auto-save on commit
   - Recall to buffer
   - Clear all option
   - Max 100 entries
