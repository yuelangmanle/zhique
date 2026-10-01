import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.zhique.core.apilot"
    compileSdk = 36
    defaultConfig {
        minSdk = 31
        // Apilot 文档当前包名（docs/android-third-party-import.md，对应 v1.24.0）——
        // 变更只改此处（BuildConfig.APILOT_PACKAGE 单点）
        buildConfigField("String", "APILOT_PACKAGE", "\"com.example.api_manager\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core:common"))
    // provider.id × protocol.id 与织雀 Provider 层同构（规格 §4.9）：直接引用 Protocol 常量防漂移
    implementation(project(":core:ai"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
