# R8 rules for the release build.
#
# Only two libraries here need help, and both for the same reason: they resolve
# types reflectively, so R8 cannot see the usage and strips what it needs.

# ── kotlinx.serialization ────────────────────────────────────────────────────
# Generated serializers are looked up by name from the companion object. Losing
# one turns every API response into a runtime crash that never shows up in a
# debug build, so keep the serializer surface of our wire types.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class io.github.lesj0610.hermes.net.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.lesj0610.hermes.net.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.lesj0610.hermes.net.**$$serializer { *; }

# ── Ktor + OkHttp ────────────────────────────────────────────────────────────
# Ktor picks its engine through a ServiceLoader; OkHttp and Okio reference
# optional platform classes that simply are not present on Android.
-keep class io.ktor.client.engine.okhttp.** { *; }
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn kotlinx.coroutines.debug.**

# ── Coroutines ───────────────────────────────────────────────────────────────
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
