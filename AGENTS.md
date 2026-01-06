# AGENTS.md - Development Notes for spweebo'ard

## Project Overview

spweebo'ard is an Android keyboard that uses SPW (Symbolic Pattern Writing) - a symbolic notation system for expressing cognitive primitives. Users type symbols like `&` (subject), `@` (perspective), `~` (potential), etc., and an on-device LLM interprets these into natural language.

**Key concept**: The keyboard doesn't just send symbols - it interprets them through an LLM to produce human-readable text.

## Repository Structure

```
spweebo'ard/
├── crates/
│   └── spweeboard-core/     # Rust library (SPW parsing, LLM inference)
│       ├── src/
│       │   ├── spw/         # SPW parser
│       │   ├── compiler/    # Prompt compiler
│       │   ├── inference/   # llama.cpp bindings
│       │   ├── ffi.rs       # UniFFI exports for mobile
│       │   └── lib.rs
│       └── Cargo.toml
├── platforms/
│   ├── android/             # Android app (Kotlin + Compose)
│   │   ├── app/
│   │   │   ├── src/main/kotlin/com/github/spwplace/spweeboard/
│   │   │   │   ├── MainActivity.kt        # Setup UI
│   │   │   │   ├── SpweeboardApplication.kt # App init, native lib loading
│   │   │   │   ├── keyboard/
│   │   │   │   │   ├── SpweeboardService.kt  # IME service + ViewModel
│   │   │   │   │   └── KeyboardLayout.kt     # Compose keyboard UI
│   │   │   │   └── model/
│   │   │   │       ├── InferenceManager.kt   # LLM inference singleton
│   │   │   │       └── ModelDownloadManager.kt # GGUF model downloads
│   │   │   └── build.gradle.kts   # Gradle build with Rust tasks
│   │   └── build.gradle.kts
│   └── ios/                 # iOS app (not yet implemented)
└── target/                  # Rust build output (workspace level)
```

## Building Android

### Prerequisites

1. **JDK 17** (not GraalVM - causes cryptic errors)
   ```bash
   export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
   ```

2. **Android SDK** with NDK 29.0.13846066
   ```bash
   export ANDROID_HOME=/Users/ember/Library/Android/sdk
   ```

3. **Rust toolchain** with Android targets:
   ```bash
   rustup target add aarch64-linux-android
   rustup target add armv7-linux-androideabi
   rustup target add x86_64-linux-android
   rustup target add i686-linux-android
   ```

4. **cargo-ndk** (v4.x):
   ```bash
   cargo install cargo-ndk
   ```

### Build Commands

```bash
cd platforms/android

# Build debug APK (includes Rust build automatically)
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Install to connected device/emulator
adb install -r app/build/outputs/apk/debug/app-debug.apk

# View logs
adb logcat -s "InferenceManager:*" "SpweeboardApplication:*"
```

### Gradle-Rust Integration

The `app/build.gradle.kts` includes custom tasks that automatically build the Rust library:

- `buildRustDebug` - Debug build, runs before `preBuild`
- `buildRustRelease` - Release build, runs before `preReleaseBuild`
- `generateUniffiBindings` - Regenerates Kotlin bindings from Rust

Key configuration:
- Uses `cargo-ndk` with `--link-libcxx-shared` to bundle libc++
- Outputs `.so` files to `app/src/main/jniLibs/`
- Currently only builds `arm64-v8a` (add more ABIs in `targetAbis` list)

### Regenerating UniFFI Bindings

If you change the Rust FFI interface (`ffi.rs`):

```bash
cd platforms/android
./gradlew generateUniffiBindings
```

This runs:
```bash
cargo run --release --features uniffi --bin uniffi-bindgen generate \
  --library target/aarch64-linux-android/release/libspweeboard_core.so \
  --language kotlin \
  --out-dir app/src/main/kotlin/uniffi
```

## Common Issues & Solutions

### "cannot locate symbol `_ZTISt20bad_array_new_length`"
**Cause**: C++ stdlib ABI mismatch
**Fix**: Ensure `--link-libcxx-shared` flag is in cargo-ndk command

### 401 error downloading models
**Cause**: HuggingFace model repo is gated (requires authentication)
**Fix**: Only use ungated repos. Current working models:
- `Qwen/Qwen2.5-0.5B-Instruct-GGUF`
- `TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF`

### UI hangs during model loading/interpretation
**Cause**: Blocking main thread
**Fix**: All LLM operations must be on `Dispatchers.IO`. The `KeyboardViewModel.interpretAsync()` handles this.

### JAVA_HOME pointing to GraalVM
**Cause**: Environment pollution
**Fix**: Explicitly set JDK 17:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

### NDK not found
**Fix**: Add to `app/build.gradle.kts`:
```kotlin
android {
    ndkVersion = "29.0.13846066"
}
```

## Architecture Notes

### Native Library Loading
`SpweeboardApplication` loads the native library in a companion object `init` block - this ensures it loads before any UniFFI calls.

### InferenceManager Singleton
- `initialize()` - Creates Rust engine instance
- `loadModel(path)` - Loads GGUF model (async, use from coroutine)
- `interpret(spw, ground)` - Returns `InterpretResult.Success` or `InterpretResult.Error`
- `isModelLoaded()` - Quick check for UI state

### KeyboardViewModel
- Manages buffer, parse state, interpretation
- `interpretAsync()` - Cancels previous job, runs on IO thread
- Parse validation (`validateSpw`) runs sync (fast enough)

### Compose in InputMethodService
Requires manual lifecycle/recomposer setup:
```kotlin
class SpweeboardService : InputMethodService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    // Must create own CoroutineScope and Recomposer
    // Must set view tree owners before setContent
}
```

## Testing the Keyboard

1. Build and install APK
2. Open app, download a model (Qwen2.5 0.5B recommended)
3. Wait for model to load (shows "Model Active")
4. Go to Settings > System > Languages & input > On-screen keyboard
5. Enable spweebo'ard
6. Open any text field, switch to spweebo'ard
7. Type SPW symbols (e.g., `&@` = "self seeing")
8. Should see interpretation preview, send button enables

## SPW Symbol Reference

| Symbol | Meaning |
|--------|---------|
| `~` | potential/becoming |
| `#` | vibration |
| `.` | ground |
| `?` | wonder/question |
| `!` | action/assertion |
| `*` | value |
| `&` | subject/self |
| `@` | perspective |
| `^` | integration |
| `<>` | concept brackets |
| `()` | scene brackets |
| `[]` | mode brackets |
| `{}` | direction brackets |

## Model Download URLs

Models must be from ungated HuggingFace repos. URL format:
```
https://huggingface.co/{org}/{repo}/resolve/main/{filename}.gguf
```

Current working models in `ModelDownloadManager.kt`:
- Qwen2.5 0.5B Q4_K_M (~400MB)
- TinyLlama 1.1B Q4_K_S (~644MB)
- Qwen2.5 0.5B Q8_0 (~530MB)

## Rust Features

The Rust crate has feature flags:
- `uniffi` - Generate FFI bindings
- `llama` - Include llama.cpp inference
- `vulkan` - Vulkan GPU backend for llama

Build command uses: `--features uniffi,llama,vulkan`

## Future Work

- iOS platform (Swift/SwiftUI)
- Streaming interpretation (show tokens as generated)
- Custom grounds creation UI
- Model fine-tuning for SPW interpretation
- Smaller/faster models optimized for mobile
