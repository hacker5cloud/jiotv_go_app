# Optimization and keep rules for Android 4.4 KitKat (API 19)
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*

# Keep data classes serialized/deserialized with Gson
-keep class com.skylake.skytv.jgorunner.ui.tvhome.** { *; }
-keep class com.skylake.skytv.jgorunner.core.data.** { *; }
-keep class com.skylake.skytv.jgorunner.data.** { *; }
-keep class com.skylake.skytv.jgorunner.activities.ChannelInfo { *; }

# ExoPlayer 2.19.1
-dontwarn com.google.android.exoplayer2.**
-keep class com.google.android.exoplayer2.** { *; }

# OkHttp 3.12.x & Conscrypt BoringSSL (KitKat LTS TLS 1.2/1.3)
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keep class org.conscrypt.** { *; }
