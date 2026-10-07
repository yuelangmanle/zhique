import java.io.File
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
        // 版本号迭代纪律：每次改动交付都 bump（versionCode 单调 +1，覆盖安装依赖它）
        versionCode = 10
        versionName = "0.2.8"
    }
    // 织雀本体的发布签名：密钥库在开发者本机（默认 ~/zhique-keystore/，密码同目录
    // password.txt），仓库零凭据字面量。CI/他人构建用环境变量覆盖路径与口令。
    // 一旦用某把密钥发布过 v0.1.0，请永远用同一把（覆盖安装语义，决策 29 同源）。
    val releaseStorePath = System.getenv("ZHIQUE_RELEASE_STORE")
        ?: "${System.getProperty("user.home")}/zhique-keystore/zhique-release.jks"
    require(!releaseStorePath.contains("..")) { "签名库路径不允许包含 ..（防路径穿越）" }
    // canonicalFile 解析符号链接与相对段，把实际路径限制在声明目录内
    val releaseStoreFile = File(releaseStorePath).canonicalFile
    require(releaseStoreFile.path.endsWith("zhique-release.jks")) { "签名库文件名须为 zhique-release.jks" }
    val releasePassFile = File(releaseStoreFile.parentFile, "password.txt").canonicalFile
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    signingConfigs {
        if (releaseStoreFile.exists()) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = System.getenv("ZHIQUE_RELEASE_STORE_PASSWORD")
                    ?: releasePassFile.readText().trim()
                keyAlias = System.getenv("ZHIQUE_RELEASE_KEY_ALIAS") ?: "zhique"
                keyPassword = System.getenv("ZHIQUE_RELEASE_KEY_PASSWORD")
                    ?: storePassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseStoreFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
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
                // 全仓源码扫描守护（AuroraGlassUiTest）用：Gradle 注入仓库根绝对路径，
                // 测试内零路径攀爬（Mimosa 路径穿越规则友好）
                test.systemProperty("zhique.repoRoot", rootDir.absolutePath)
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
    implementation(libs.androidx.biometric)

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
    implementation(project(":core:telemetry"))

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
