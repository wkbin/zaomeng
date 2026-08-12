// Ktor 后端服务模块（Android 内嵌 localhost 服务器，迁移自 main 分支 Python 后端）。
// KMP：业务逻辑（路由/服务/模型）在 commonMain；Android/JVM 平台差异用 expect/actual 隔离。
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val hostOs = System.getProperty("os.name").lowercase()
val isMacHost = hostOs == "mac os x" || hostOs == "macos" || hostOs == "darwin"

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    android {
        namespace = "top.wkbin.zaomeng.server"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
        androidResources {
            enable = true
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
            api(project(":core:contracts"))
            api(project(":core:runtime"))
            api(project(":server:storage"))
            implementation(project(":server:llm"))
            implementation(project(":server:http"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.okio)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio.kmp)
            implementation(libs.ktor.server.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.server.auth)
            implementation(libs.ktor.server.status.pages)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(project(":plugins-api"))
            implementation(project(":builtin-plugins"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.snakeyaml.engine.kmp)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.okhttp)
            implementation(libs.kotlinx.coroutines.android)
        }
        jvmMain.dependencies {
            implementation(libs.snakeyaml)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.okhttp)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test.junit)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotlin.test.junit)
            implementation(libs.ktor.server.test.host)
        }
        if (isMacHost) {
            iosMain {
                dependencies {
                    implementation(libs.ktor.client.darwin)
                    implementation(libs.snakeyaml.engine.kmp)
                    implementation(libs.kitearchive)
                }
            }
        }
        jvmTest.dependencies {
            implementation(libs.snakeyaml.engine.kmp)
            implementation(libs.kitearchive)
        }
    }
}
