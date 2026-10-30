pluginManagement {
    repositories {
        // 华为云镜像 - Google Maven
        maven {
            url = uri("https://mirrors.huaweicloud.com/repository/google")
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google\\.mlkit.*")
                includeGroupByRegex("com\\.google\\.ai.*")
            }
        }
        // 华为云镜像 - Central
        maven {
            url = uri("https://mirrors.huaweicloud.com/repository/maven")
        }
        // 华为云镜像 - Gradle Plugin
        maven {
            url = uri("https://mirrors.huaweicloud.com/repository/gradle")
        }
        // 华为云镜像 - JetBrains
        maven {
            url = uri("https://mirrors.huaweicloud.com/repository/jetbrains")
        }
        // 备用官方源
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://mirrors.huaweicloud.com/repository/google") }
        maven { url = uri("https://mirrors.huaweicloud.com/repository/maven") }
        maven { url = uri("https://mirrors.huaweicloud.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "JARVIS"
include(":app")
