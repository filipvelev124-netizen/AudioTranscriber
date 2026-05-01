-keep class org.vosk.** { *; }
-dontwarn org.vosk.**

# JNA — accessed by reflection at runtime via native bridge
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keepclassmembers class * extends com.sun.jna.Structure { !static <fields>; }
-dontwarn com.sun.jna.**

# OkHttp / Okio
# -dontwarn alone suppresses build warnings but does NOT prevent R8 from
# stripping OkHttp's reflection-loaded platform classes (CertificatePinner,
# internal platform detection, etc.). The -keep rules are required for the
# model download to work in release / minified builds.
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**
