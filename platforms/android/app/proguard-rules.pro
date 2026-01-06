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

# Keep UniFFI generated classes
-keep class com.github.spwplace.spweeboard.uniffi.** { *; }
-keep class uniffi.** { *; }
-keep class uniffi.spweeboard_core.** { *; }

# JNA - required for UniFFI
-dontwarn java.awt.*
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }

# Keep JNA Pointer and related classes
-keepclassmembers class com.sun.jna.Pointer {
    long peer;
}
-keepclassmembers class * extends com.sun.jna.Structure {
    <fields>;
}

# Keep UniFFI callback interfaces
-keep interface uniffi.** { *; }
-keep class * implements uniffi.spweeboard_core.SpwStreamCallback { *; }
