# Preserve the generic signatures and annotations used by Retrofit and Kotlin.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations, AnnotationDefault

# Kotlin serialization generates serializers at compile time. Keep serializer
# lookup metadata for any serializable model reached through a companion object.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers,allowoptimization,allowobfuscation class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclasseswithmembers,allowoptimization,includedescriptorclasses class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Android instantiates these classes from the manifest rather than direct calls.
-keep class top.wkbin.zaomeng.app.ZaomengApplication { *; }
-keep class top.wkbin.zaomeng.app.MainActivity { *; }
-keep class top.wkbin.zaomeng.backend.DistillationForegroundService { *; }
-keep class top.wkbin.zaomeng.backend.NovelConversionForegroundService { *; }

# Optional JVM-only annotations referenced by dependency metadata.
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
