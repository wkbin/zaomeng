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

kotlin {
    jvm()

    if (isMacHost) {
        iosArm64()
        iosSimulatorArm64()
    }

    android {
        namespace = "top.wkbin.zaomeng.feature.persona"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:contracts"))
            implementation(project(":core:domain"))
            implementation(project(":data:repository"))
            implementation(project(":ui:shared"))
            implementation(libs.bundles.compose.ui)
            implementation(libs.compose.lifecycle.runtime)
            api(libs.compose.lifecycle.viewmodel)
            implementation(libs.bundles.koin.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
