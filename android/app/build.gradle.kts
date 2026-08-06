import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.chaquopy)
}

val repositoryRoot = rootProject.projectDir.parentFile
val generatedPythonSources = layout.buildDirectory.dir("generated/python/main")
val configuredBuildPython = providers.gradleProperty("chaquopyBuildPython").orNull
val signingPropertiesFile = rootProject.file("keystore.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.exists()) {
        signingPropertiesFile.inputStream().use { input -> load(input) }
    }
}
val syncSharedPythonSources by tasks.registering(Sync::class) {
    from(repositoryRoot) {
        include("src/**")
        include("rules/**")
        include("zaomeng-skill/**")
        exclude("src/web/static/**")
        exclude("zaomeng-skill/assets/**")
        exclude("**/__pycache__/**")
        exclude("**/*.pyc")
        exclude("**/*.pyo")
    }
    into(generatedPythonSources)
}

android {
    namespace = "top.wkbin.zaomeng"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "top.wkbin.zaomeng"
        minSdk = 24
        targetSdk = 36
        versionCode = 7
        versionName = "1.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (signingPropertiesFile.exists()) {
            create("release") {
                val storePath = signingProperties.getProperty("storeFile").orEmpty()
                storeFile = rootProject.file(storePath)
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (signingPropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".test"
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        when {
            !configuredBuildPython.isNullOrBlank() -> buildPython(configuredBuildPython)
            System.getProperty("os.name").lowercase().contains("windows") -> buildPython("python")
        }
        extractPackages("src")
        pip {
            install("PyYAML==6.0.3")
            install("fastapi==0.119.1")
            install("pydantic==1.10.24")
            install("uvicorn==0.34.3")
            install("python-multipart==0.0.20")
            install("requests>=2.31.0,<3.0.0")
        }
    }
    sourceSets {
        getByName("main") {
            srcDir(generatedPythonSources)
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(syncSharedPythonSources)
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("PythonSources") }
    .configureEach {
        dependsOn(syncSharedPythonSources)
    }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
