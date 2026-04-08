# Keep MediaPlaybackService (started via intent actions)
-keep class com.yaytsa.app.MediaPlaybackService { *; }

# Keep EncryptedSharedPreferences internals
-keep class androidx.security.crypto.** { *; }

# Suppress Tink annotation warnings
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
