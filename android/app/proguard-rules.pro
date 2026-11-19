# ProGuard / R8 rules for Dastyab Android
# Preserve stack trace file names and line numbers for CrashHandler diagnostics.
-keepattributes SourceFile,LineNumberTable,*Annotation*
-renamesourcefileattribute SourceFile

# Keep all DastYab application classes, Android entry points, and models intact.
-keep class com.dastyab.app.** { *; }
-keepclassmembers class com.dastyab.app.** { *; }

# Keep AndroidManifest components explicitly so R8 never prunes entry points.
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.service.quicksettings.TileService
-keep public class * extends android.appwidget.AppWidgetProvider

-keepnames class kotlinx.** { *; }
-dontwarn kotlinx.**

# Keep AndroidX WebKit ProxyController classes used for per-app browser proxy routing
-keep class androidx.webkit.** { *; }
-dontwarn androidx.webkit.**

# Keep Chaquopy Python runtime classes and native JNI bridge methods
-keep class com.chaquo.python.** { *; }
-keepclassmembers class com.chaquo.python.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-dontwarn com.chaquo.python.**

