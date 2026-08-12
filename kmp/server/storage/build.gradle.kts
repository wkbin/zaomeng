import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val hostOs = System.getProperty("os.name").lowercase()
val isMacHost = hostOs == "mac os x" || hostOs == "macos" || hostOs == "darwin"

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room3)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    android {
        namespace = "top.wkbin.zaomeng.server.storage"
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

    if (isMacHost) {
        iosArm64()
        iosSimulatorArm64()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:runtime"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
            api(libs.androidx.room3.runtime)
            api(libs.androidx.sqlite.bundled)
        }
        androidMain.dependencies {
            implementation(libs.snakeyaml.engine.kmp)
        }
        jvmMain.dependencies {
            implementation(libs.snakeyaml)
        }
        if (isMacHost) {
            iosMain.dependencies {
                implementation(libs.snakeyaml.engine.kmp)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspJvm", libs.androidx.room3.compiler)
    add("kspAndroid", libs.androidx.room3.compiler)
    if (isMacHost) {
        add("kspIosArm64", libs.androidx.room3.compiler)
        add("kspIosSimulatorArm64", libs.androidx.room3.compiler)
    }
}
