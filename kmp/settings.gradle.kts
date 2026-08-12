// CI（GitHub Actions 等境外环境）直连 maven.aliyun.com 不稳定：Maven 仓库连接错误会
// 直接判定解析失败，不会回退到下一个仓库。因此 CI 下官方仓库优先、阿里云镜像兜底；
// 本机（国内网络）保持阿里云优先以加速依赖下载。
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/central")
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central") {
            content {
                // Aliyun 镜像未同步 io.github.yuroyami 的 KMP 变体构件（kitearchive-jvm 404），
                // 该组强制走 mavenCentral()，避免解析成 native klib。
                excludeGroupByRegex("io\\.github\\.yuroyami.*")
            }
        }
    }
}

rootProject.name = "Zaomeng"
include(":core:contracts")
include(":core:domain")
include(":core:runtime")
include(":data:remote")
include(":data:repository")
include(":feature:bookshelf")
include(":feature:cards")
include(":feature:chapters")
include(":feature:chat")
include(":feature:crossover")
include(":feature:importbook")
include(":feature:library")
include(":feature:originalknowledge")
include(":feature:persona")
include(":feature:redistill")
include(":feature:relations")
include(":feature:rundetail")
include(":feature:sessions")
include(":feature:settings")
include(":feature:storyrecap")
include(":feature:timeline")
include(":feature:update")
include(":server")
include(":server:storage")
include(":server:llm")
include(":server:http")
include(":ui:shared")
include(":plugins-api")
include(":builtin-plugins")
include(":app:shared")
include(":app:androidApp")
include(":app:desktopApp")
