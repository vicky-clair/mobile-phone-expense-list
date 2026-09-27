// 统一固定构建插件版本；AGP 使用内置 Kotlin，Compose 编译器和 KSP 单独声明。
plugins {
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.3.9" apply false
}
