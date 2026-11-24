pluginManagement {
    repositories {
        // 阿里云镜像放最前面：国内直连 dl.google.com 拉几百个 artifact 会非常慢，
        // 甚至中途超时。镜像后面保留官方源兜底，镜像没同步到的新版本还能拿到。
        maven {
            url = uri("https://maven.aliyun.com/repository/google")
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
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
    // Java 工具链自动下载。AGP 9 内置 Kotlin 会按 compileOptions 解析 JVM 目标，
    // 保留它可以在工具链缺失时自动拉取，而不是直接编译失败。
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
    }
}

rootProject.name = "InkNote"
include(":app")
