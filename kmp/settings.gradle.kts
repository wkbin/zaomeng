// CI（GitHub Actions 等境外环境）直连 maven.aliyun.com 不稳定：Maven 仓库连接错误会
// 直接判定解析失败，不会回退到下一个仓库。因此 CI 下官方仓库优先、阿里云镜像兜底；
// 本机（国内网络）保持阿里云优先以加速依赖下载。
pluginManagement {
    repositories {
        if (System.getenv("CI") == "true") {
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
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/central")
        if (System.getenv("CI") != "true") {
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
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI") == "true") {
            google()
            mavenCentral()
        }
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central") {
            content {
                // Aliyun 镜像未同步 io.github.yuroyami 的 KMP 变体构件（kitearchive-jvm 404），
                // 该组强制走 mavenCentral()，避免解析成 native klib。
                excludeGroupByRegex("io\\.github\\.yuroyami.*")
            }
        }
        if (System.getenv("CI") != "true") {
            google()
            mavenCentral()
        }
    }
}

rootProject.name = "Zaomeng"
include(":server")
include(":plugins-api")
include(":builtin-plugins")
include(":app:shared")
include(":app:androidApp")
include(":app:desktopApp")
