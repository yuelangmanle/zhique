import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.zhique.core.export"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

// M6 Task 6.1：两模板壳 assembleRelease 产物（unsigned，注入底版）收割进
// :core:export assets，随织雀 APK 分发；导出管线运行时解包注入。
val templateMinApk = project(":template-min").layout.buildDirectory
    .file("outputs/apk/release/template-min-release-unsigned.apk")
val templateFullApk = project(":template-full").layout.buildDirectory
    .file("outputs/apk/release/template-full-release-unsigned.apk")

val harvestTemplates = tasks.register<Copy>("harvestTemplates") {
    group = "zhique"
    description = "把模板壳 assembleRelease 产物拷入 :core:export assets/templates/"
    dependsOn(":template-min:assembleRelease", ":template-full:assembleRelease")
    from(templateMinApk, templateFullApk)
    rename { it.removeSuffix("-release-unsigned.apk") + ".apk" }
    into(layout.projectDirectory.dir("src/main/assets/templates"))
}

// 测试夹具：真实模板 APK 供注入/签名/校验管线端到端测试（构建期提供，不落仓库）
val templateMinApkPath = templateMinApk.get().asFile.absolutePath
val templateFullApkPath = templateFullApk.get().asFile.absolutePath

tasks.named("preBuild") { dependsOn(harvestTemplates) }

tasks.withType<Test>().configureEach {
    dependsOn(":template-min:assembleRelease", ":template-full:assembleRelease")
    systemProperty("zhique.template.minApk", templateMinApkPath)
    systemProperty("zhique.template.fullApk", templateFullApkPath)
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:project"))
    implementation(libs.apksig)
    implementation(libs.zipflinger)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
