pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/central")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central") {
            content {
                // Aliyun 镜像未同步 io.github.yuroyami 的 KMP 变体构件（kitearchive-jvm 404），
                // 该组强制走 mavenCentral()，避免解析成 native klib。
                excludeGroupByRegex("io\\.github\\.yuroyami.*")
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "Zaomeng"
include(":server")
include(":plugins-api")
include(":builtin-plugins")
include(":app:shared")
include(":app:androidApp")
include(":app:desktopApp")
