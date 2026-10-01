import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // 债务收敛（M2 探明 → M9 修复）：release 变体合并清单不含 ui-test-manifest 的
                // ComponentActivity（该 AAR 只随 debugImplementation 进 debug 变体），Robolectric
                // 解析 activity 失败 → Compose UI 测试在 testReleaseUnitTest 全量挂 81 例。
                // 命名约定守卫：Compose UI 测试按 *UiTest/*ScreenTest/… 命名，仅 debug 变体执行，
                // 纯 JVM/Robolectric 逻辑测试两个变体照常跑。
                if (test.name.contains("Release", ignoreCase = true)) {
                    test.exclude(
                        "**/*UiTest.class",
                        "**/*ScreenTest.class",
                        "**/*ScreenApilotTest.class",
                        "**/*CardTest.class",
                        "**/*EntryTest.class",
                        "**/*HostTest.class",
                    )
                }
            }
        }
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)

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

    implementation(libs.sora.editor)
    implementation(libs.sora.editor.language.textmate)

    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.compose.ui.test.manifest)
}
