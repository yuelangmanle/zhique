import java.net.URI
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// eruda（第三方移动端调试面板）不在源码树内：构建期按锁定版本拉取并校验 SHA-256，
// 供应链完整性与仓库审计面（不审计第三方压缩产物）兼得。换版本 = 改版本号 + 重算哈希。
val erudaVersion = "3.4.3"
val erudaSha256 = "332f95b14b1dc53cdbe6042e0ea95ac6025ac691c285d51b647c64360fe939e2"
val erudaAssetsDir = layout.buildDirectory.dir("generated/eruda-assets")

val fetchEruda by tasks.registering {
    val target = erudaAssetsDir.map { it.file("eruda.js") }
    outputs.file(target)
    doLast {
        val out = target.get().asFile
        fun sha256(f: File): String =
            MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        // 已缓存且哈希一致 → 复用；存在但校验失败 → 视同损坏重拉（质量审查次要-5）
        if (out.exists()) {
            if (sha256(out) == erudaSha256) return@doLast
            out.delete()
        }
        out.parentFile.mkdirs()
        // npm registry 官方 tarball（registry.npmjs.org，非镜像/CDN 转译产物）
        val tgz = out.parentFile.resolve("eruda.tgz")
        try {
            tgz.writeBytes(URI("https://registry.npmjs.org/eruda/-/eruda-$erudaVersion.tgz").toURL().readBytes())
        } catch (e: java.io.IOException) {
            throw GradleException(
                "eruda $erudaVersion 拉取失败（无网络或 npm registry 不可达）：${e.message}。" +
                    "联网后重试，或手动放置 $out（SHA-256 须等于 $erudaSha256）",
                e,
            )
        }
        val pkgDir = out.parentFile.resolve("eruda-pkg")
        pkgDir.deleteRecursively()
        pkgDir.mkdirs()
        // 解包取 package/eruda.js（tar 为系统自带；Windows 需 bsdtar，随 Win10+ 内置）
        val proc = ProcessBuilder("tar", "xzf", tgz.absolutePath, "-C", pkgDir.absolutePath)
            .redirectErrorStream(true).start()
        if (proc.waitFor() != 0) throw GradleException("eruda tarball 解包失败：" + proc.inputStream.readBytes().decodeToString())
        val entry = pkgDir.resolve("package/eruda.js")
        val hex = sha256(entry)
        if (hex != erudaSha256) {
            throw GradleException("eruda $erudaVersion SHA-256 校验失败：期望 $erudaSha256，实际 $hex")
        }
        // 原子落盘：先写临时文件再 rename（防中断留下半截资产蒙混过缓存检查）
        val tmp = File(out.absolutePath + ".tmp")
        entry.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(out)) throw GradleException("eruda 资产落盘失败（rename）：${tmp.absolutePath}")
        tgz.delete(); pkgDir.deleteRecursively()
    }
}

android {
    namespace = "com.zhique.core.web"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].assets.srcDir(erudaAssetsDir)
}

tasks.named("preBuild") { dependsOn(fetchEruda) }

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:project"))
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
