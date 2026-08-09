// 内置插件实现模块（迁移自 main 分支 src/builtin_plugins/*，Python → Kotlin）。
// KMP：纯 Kotlin 逻辑放 commonMain，androidLibrary 供 App 使用，jvm() 供桌面/测试。
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val hostOs = System.getProperty("os.name").lowercase()
val isMacHost = hostOs == "mac os x" || hostOs == "macos" || hostOs == "darwin"

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    android {
        namespace = "top.wkbin.zaomeng.plugins.builtin"
        compileSdk = 36
        minSdk = 24
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    jvm()

    // Apple target 只能在 macOS 主机上编译；Windows/CI(Linux) 自动跳过，保持本机构建可跑。
    if (isMacHost) {
        iosArm64()
        iosSimulatorArm64()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":plugins-api"))
        }
    }
}
