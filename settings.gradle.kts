// 插件仓库只将 Android/AndroidX 相关分组交给 Google，其他依赖使用中央仓库。
pluginManagement {
    repositories {
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("androidx\\..*"); includeGroupByRegex("com\\.google\\.android.*"); includeGroupByRegex("com\\.google\\.testing.*") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
// 禁止子模块自行添加仓库，让依赖来源和解析顺序可复现。
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("androidx\\..*"); includeGroupByRegex("com\\.google\\.android.*"); includeGroupByRegex("com\\.google\\.testing.*") } }
        mavenCentral()
    }
}
// 工程内部名称和 applicationId 保持稳定，用户看到的名称由 Manifest 决定。
rootProject.name = "FoldLedger"
include(":app")
