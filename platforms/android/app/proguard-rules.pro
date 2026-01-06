# spweebo'ard ProGuard Rules

# Keep Kotlin metadata
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }

# Keep Compose classes
-keep class androidx.compose.** { *; }

# Keep InputMethodService
-keep class * extends android.inputmethodservice.InputMethodService

# Keep JNI methods for Rust FFI (when integrated)
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep UniFFI generated classes (when integrated)
-keep class com.github.spwplace.spweeboard.uniffi.** { *; }
