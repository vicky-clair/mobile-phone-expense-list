// Android 应用、Compose 编译器以及 Room 所需的 KSP 代码生成。
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    // 升级时保持包名和签名一致，避免安装成另一个应用而无法读取原账本。
    namespace = "cn.foldledger"
    // 默认 API 37；允许临时指定 API 36 进行构建，不代表新系统真机兼容性已验证。
    compileSdk = providers.gradleProperty("ledger.compileSdk").orNull?.toInt() ?: 37
    buildToolsVersion = "36.1.0"
    defaultConfig {
        applicationId = "cn.foldledger"
        // 最低 Android 13，targetSdk 与编译 SDK 分开管理，升级目标版本前须验收后台行为。
        minSdk = 33
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    // 邮件与激活依赖包含同名说明资源，打包时保留一份以消除冲突。
    packaging { resources.pickFirsts += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Robolectric 加载 Android 资源及固定仓库中的模拟系统，不使用连接的真实手机。
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2") }
    }
}
// 导出并保留数据库 schema，后续结构变更必须据此编写迁移。
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    // 界面与生命周期依赖；Compose 组件版本由 BOM 统一管理。
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3.adaptive:adaptive:1.1.0")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("androidx.biometric:biometric:1.1.0")
    // 本地账本/日报记录使用 Room，普通设置使用 DataStore。
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    // 可延迟的后台日报调度及个人 SMTP 发送，不使用常驻前台保活。
    implementation("androidx.work:work-runtime-ktx:2.10.3")
    implementation("org.eclipse.angus:jakarta.mail:2.0.3")
    implementation("org.eclipse.angus:angus-activation:2.0.2")
    // 本地单元测试、Android 行为模拟及协程测试，均不配置真实邮箱凭据。
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
