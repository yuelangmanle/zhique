import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.zhique.export.full"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.zhique.export.full"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    // M6 导出模板：release 产物即注入底版（unsigned，由端上 KeystoreManager 签名）
    lint { checkReleaseBuilds = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main") {
        // 两壳共用模板壳运行时（单份源码，manifest 差异化权限）
        java.srcDir(layout.projectDirectory.dir("../template-common/src/main/kotlin"))
        assets.srcDir(layout.projectDirectory.dir("../template-common/src/main/assets"))
        // launcher 图标资源同样单份（M6 偏差③：icon_indigo/icon_slate 自适应图标预设）
        res.srcDir(layout.projectDirectory.dir("../template-common/src/main/res"))
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core:web"))
    implementation(libs.androidx.webkit)
    implementation(project(":core:permission"))
    implementation(project(":core:project"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
