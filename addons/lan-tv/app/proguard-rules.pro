-keep class io.github.cleanyourtv.lantv.** { *; }
-keepclassmembers class io.github.cleanyourtv.lantv.** { *; }

# Media3 / ExoPlayer (SessionImpl implements Player.Listener)
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep player/setup logs (proguard-android-optimize strips Log.i by default).
-keepclassmembers class android.util.Log {
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
