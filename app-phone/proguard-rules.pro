# Add project specific ProGuard rules here.
#
# Phone APK does NOT include NanoHTTPD or :core-tv-server (com.omnio.tv.core.server),
# so the corresponding keep rules from :app-tv are intentionally absent here.

# ── Moshi ──────────────────────────────────────────────────────────────────────
-keep class com.squareup.moshi.** { *; }
-keep class **JsonAdapter { *; }
-keepclassmembers class ** {
    @com.squareup.moshi.Json <fields>;
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}
-keepclasseswithmembers class * {
    @com.squareup.moshi.JsonClass <init>(...);
}

# ── Gson (TypeToken reflection used by addon/plugin code in :core-platform) ───
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ── Retrofit ───────────────────────────────────────────────────────────────────
-keepattributes Signature
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep class com.omnio.tv.data.remote.api.** { *; }

# ── OkHttp ─────────────────────────────────────────────────────────────────────
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── Data classes (DTOs, domain models) ────────────────────────────────────────
-keep class com.omnio.tv.data.remote.dto.** { *; }
-keep class com.omnio.tv.domain.model.** { *; }

# ── Kotlin ─────────────────────────────────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes RuntimeVisibleAnnotations

# ── QuickJS plugin runtime (in :core-platform) ────────────────────────────────
-keep class com.dokar.quickjs.** { *; }
-keepclassmembers class com.dokar.quickjs.** { *; }
-keep class com.omnio.tv.core.plugin.** { *; }
-keepclassmembers class com.omnio.tv.core.plugin.** { *; }

# ── ExoPlayer / Media3 (forked AARs in :core-player) ──────────────────────────
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-keep class androidx.media.** { *; }
-keep class androidx.media3.decoder.** { *; }
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.ui.** { *; }
-keep class com.google.android.exoplayer2.** { *; }
-keep interface com.google.android.exoplayer2.** { *; }
-keep class com.google.android.exoplayer2.ext.** { *; }

# ── MPV (native JNI callbacks via :core-player) ───────────────────────────────
-keep class is.xyz.mpv.** { *; }

# ── Supabase / Ktor / Kotlinx Serialization ───────────────────────────────────
-keep class io.github.jan.supabase.** { *; }
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class com.omnio.tv.data.remote.supabase.** { *; }
-keepclassmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── ZXing core (QR scanning on phone) ─────────────────────────────────────────
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ── General ────────────────────────────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
