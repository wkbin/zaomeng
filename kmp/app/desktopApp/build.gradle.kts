// 桌面入口壳（Compose Multiplatform）：渲染共享 UI（:app:shared），
// 并内嵌启动 :server 后端，行为与 Android 内嵌服务一致。
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

dependencies {
    implementation(project(":app:shared"))
    implementation(project(":server"))
    implementation(libs.koin.core)
    implementation(libs.okio)
    implementation(libs.filekit.core)
    implementation(libs.bundles.jna)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.components.resources)
    implementation(libs.compose.ui.tooling.preview)
    runtimeOnly(libs.slf4j.simple)
}

compose.desktop {
    application {
        mainClass = "top.wkbin.zaomeng.app.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "造梦"
            packageVersion = "2.1.1"
            // 运行期用到 sun.misc.Unsafe（jpackage 裁剪运行时默认不含 jdk.unsupported）
            modules("jdk.unsupported")
            windows {
                // 安装包图标、桌面快捷方式与开始菜单项
                iconFile.set(file("packaging/zaomeng_logo.ico"))
                shortcut = true
                menu = true
                menuGroup = "造梦"
            }
        }
    }
}
