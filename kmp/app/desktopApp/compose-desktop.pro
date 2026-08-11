# OkHttp detects these optional providers at runtime. They are intentionally not
# bundled in the regular Windows JVM distribution, so ProGuard cannot resolve them.
-dontwarn com.oracle.svm.core.annotate.**
-dontwarn org.graalvm.nativeimage.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
