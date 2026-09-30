import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.zhique.runner"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.zhique.runner"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(project(":core:common"))
    implementation(project(":core:project"))
    implementation(project(":core:paste"))
    implementation(project(":core:web"))
    implementation(project(":core:ai"))
    implementation(project(":core:agent"))
    implementation(project(":core:permission"))
    implementation(project(":core:export"))
    implementation(project(":core:publish"))
    implementation(project(":core:apilot"))
}
