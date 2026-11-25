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
    }
}

rootProject.name = "AI Pocket Chat"
include(":app")
// :baselineprofile 已恢复（依赖升级轮三 2026-09-24）：2026-06 因 benchmark 只有 1.5.0-alpha 支持 AGP 9.x 而暂停，
// 1.5.0 正式版发布后恢复。本模块只做 Baseline Profile 生成 + 启动宏基准；重新生成 profile 须连设备跑，量化以真机批为准。
include(":baselineprofile")
