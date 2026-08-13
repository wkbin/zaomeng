import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val hostOs = System.getProperty("os.name").lowercase()
val isMacHost = hostOs == "mac os x" || hostOs == "macos" || hostOs == "darwin"

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

compose.resources {
    publicResClass = true
}

kotlin {
    jvm()

    if (isMacHost) {
        iosArm64()
        iosSimulatorArm64()
    }

    android {
        namespace = "top.wkbin.zaomeng.ui.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        androidResources {
            enable = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":data:remote"))
            implementation(libs.bundles.compose.ui)
            implementation(libs.material.kolor)
            implementation(libs.bundles.filekit.compose)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.okio)
            implementation(libs.okio)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
        }
        if (isMacHost) {
            iosMain.dependencies {
                implementation(libs.kitearchive)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
