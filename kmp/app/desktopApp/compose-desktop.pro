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
