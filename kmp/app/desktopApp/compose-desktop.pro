# ProGuard 7.7.0 can generate invalid stack map frames while optimizing large
# Compose-generated methods such as ZaomengTheme. Keep shrinking and obfuscation,
# but disable the unsafe bytecode optimization pass for the desktop distribution.
-dontoptimize

# OkHttp detects these optional providers at runtime. They are intentionally not
# bundled in the regular Windows JVM distribution, so ProGuard cannot resolve them.
-dontwarn com.oracle.svm.core.annotate.**
-dontwarn org.graalvm.nativeimage.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# JNI binds native libraries to exact JVM class, method, and descriptor names.
# Keep every live native entry point stable for SQLite, Skiko, and other runtimes.
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}

# FileKit uses JNA reflection and COM vtable wrappers for Windows file dialogs.
# Keep JNA internals plus FileKit's Windows bindings and structure field layouts.
-keep class com.sun.jna.** { *; }
-keep class io.github.vinceglb.filekit.dialogs.platform.windows.** { *; }
-keep class * extends com.sun.jna.Structure { *; }
-keep interface * extends com.sun.jna.Library { *; }
-keep interface * extends com.sun.jna.Callback { *; }

# Ktor discovers JSON serialization through ServiceLoader. The provider name is
# stored in META-INF/services, so it must not be removed or renamed by ProGuard.
-keep class io.ktor.serialization.kotlinx.json.KotlinxSerializationJsonExtensionProvider { *; }

# Room loads the generated database implementation by deriving its class name at
# runtime (ZaomengDatabase -> ZaomengDatabase_Impl), which ProGuard cannot infer.
-keep class top.wkbin.zaomeng.db.ZaomengDatabase { *; }
-keep class top.wkbin.zaomeng.db.ZaomengDatabase_Impl { *; }
