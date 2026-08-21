# Keep Kotlinx Serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.cone.agent.**$$serializer { *; }
-keepclassmembers class com.cone.agent.** {
    *** Companion;
}

# Retrofit
-keepattributes Signature, Exceptions
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# ML Kit
-keep class com.google.mlkit.** { *; }

# Hilt / Dagger keeps handled by their consumer rules.

# Vosk offline speech recognition (uses JNA — must not be obfuscated/stripped).
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
-dontwarn org.vosk.**
