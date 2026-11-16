# ProGuard rules for Dastyab Android
# Keep Kotlin stdlib and our app code intact since the app is small.
-keep class com.dastyab.app.** { *; }
-keepnames class kotlinx.** { *; }

# Keep reflection-friendly members used by the app.
-keepclassmembers class com.dastyab.app.MainActivity { *; }

# Don't warn about missing references for the tiny stdlib-only app.
-dontwarn kotlinx.**
