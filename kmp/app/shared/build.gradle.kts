// 共享 UI 层（Compose Multiplatform）：commonMain 放跨平台 UI，
// Android 由 androidApp 消费，桌面由 desktopApp 消费；复用 server 的内嵌后端。
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val hostOs = System.getProperty("os.name").lowercase()
val isMacHost = hostOs == "mac os x" || hostOs == "macos" || hostOs == "darwin"

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 生成的 Res 资源类跨模块可见（desktopApp 窗口图标等直接引用 composeResources）
compose.resources {
    publicResClass = true
}

kotlin {
    jvm()

    // Apple target 只能在 macOS 主机上编译；Windows/CI(Linux) 自动跳过，保持本机构建可跑。
    if (isMacHost) {
        listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
            iosTarget.binaries.framework {
                baseName = "Shared"
                isStatic = true
                // 导出 ViewModel API，Swift 可直接访问（不带 Lifecycle_viewmodel 前缀）
                export(libs.compose.lifecycle.viewmodel)
            }
        }
    }

    android {
        namespace = "top.wkbin.zaomeng.app.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:contracts"))
            implementation(project(":core:domain"))
            implementation(project(":core:runtime"))
            implementation(project(":data:remote"))
            implementation(project(":data:repository"))
            implementation(project(":feature:bookshelf"))
            implementation(project(":feature:cards"))
            implementation(project(":feature:chapters"))
            implementation(project(":feature:chat"))
            implementation(project(":feature:crossover"))
            implementation(project(":feature:importbook"))
            implementation(project(":feature:library"))
            implementation(project(":feature:originalknowledge"))
            implementation(project(":feature:persona"))
            implementation(project(":feature:pluginbuilder"))
            implementation(project(":feature:redistill"))
            implementation(project(":feature:relations"))
            implementation(project(":feature:rundetail"))
            implementation(project(":feature:sessions"))
            implementation(project(":feature:settings"))
            implementation(project(":feature:storyrecap"))
            implementation(project(":feature:timeline"))
            api(project(":feature:update"))
            api(project(":ui:shared"))
            implementation(libs.bundles.compose.ui)
            implementation(libs.compose.lifecycle.runtime)
            api(libs.compose.lifecycle.viewmodel)
            implementation(libs.compose.lifecycle.viewmodel.navigation3)
            implementation(libs.jetbrains.navigation3.ui)
            implementation(libs.androidx.navigationevent.compose)
            api(libs.paging.common)
            implementation(libs.paging.compose)
            implementation(libs.bundles.koin.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.okio)
            implementation(libs.bundles.filekit.compose)
            implementation(libs.kotlinx.io.okio)
            implementation(libs.material.kolor)
            implementation(libs.bundles.androidx.datastore)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        androidMain.dependencies {
            implementation(project(":server"))
            implementation(libs.compose.ui.tooling)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.androidx.core.ktx)
        }
        jvmMain.dependencies {
            implementation(project(":server"))
            implementation(libs.skiko.awt)
            // viewModelScope 依赖 Dispatchers.Main（桌面端由 coroutines-swing 提供）
            implementation(libs.kotlinx.coroutines.swing)
        }
        if (isMacHost) {
            iosMain {
                dependencies {
                    implementation(project(":server"))
                    implementation(libs.kitearchive)
                }
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.paging.common)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.ui.tooling)
}
