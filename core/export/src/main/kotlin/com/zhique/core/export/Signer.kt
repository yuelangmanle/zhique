package com.zhique.core.export

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * APK 签名与编程校验（M6 Task 6.2，apksig）：v1+v2+v3 三重签名 → [ApkVerifier]
 * 断言。v1（JAR 签名）虽自 minSdk 24 起非必需，但**部分 OEM 安装器（小米/
 * HyperOS 文件管理器路径、"解析包失败"类报错）对纯 v2/v3 包校验更严**——
 * 三重签名兼容性最大，代价仅百 KB 级（真机反馈：导出包安装失败修复）。
 * 对齐由注入阶段（zipflinger 保持既有条目对齐）与签名器协同完成。
 */
class Signer {

    data class Key(val privateKey: PrivateKey, val certificate: X509Certificate) {
        /** 证书 SHA-256（hex 小写，ExportRecord.certSha256 的值源）。 */
        val certSha256: String get() = CertFingerprints.sha256(certificate)
    }

    /** [input] → v1+v2+v3 签名 → [output]。 */
    fun sign(input: File, output: File, key: Key) {
        output.parentFile?.mkdirs()
        val config = ApkSigner.SignerConfig.Builder(ALIAS, key.privateKey, listOf(key.certificate)).build()
        ApkSigner.Builder(listOf(config))
            .setInputApk(input)
            .setOutputApk(output)
            .setMinSdkVersion(MIN_SDK)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setOtherSignersSignaturesPreserved(false)
            .setAlignmentPreserved(true)
            .build()
            .sign()
    }

    /**
     * ApkVerifier 编程校验（三视角）：
     * - minSdk 21 视角校验 v1（JAR 签名）存在且有效——apksig 在 minCheckedPlatform
     *   ≥24 时会**跳过 v1 校验**（返回 false），只有低平台视角才真正核验 v1；
     * - minSdk 24 视角校验 v2（v2 生效平台）；
     * - minSdk 31 视角校验整体与 v3（交付平台，v3 自 P 起优先于 v2）。
     * 返回签名证书 SHA-256；失败抛 [IllegalStateException]。
     */
    fun verify(apk: File): String {
        val v1Result = ApkVerifier.Builder(apk)
            .setMinCheckedPlatformVersion(V1_MIN_SDK)
            .build()
            .verify()
        check(v1Result.isVerified && v1Result.isVerifiedUsingV1Scheme()) {
            "APK 未通过 v1 签名校验（OEM 安装器兼容要求）"
        }

        val v2Result = ApkVerifier.Builder(apk)
            .setMinCheckedPlatformVersion(V2_MIN_SDK)
            .build()
            .verify()
        check(v2Result.isVerified) { "APK 未通过整体校验（v2 视角）" }
        check(v2Result.isVerifiedUsingV2Scheme()) { "APK 未通过 v2 签名校验" }

        val result = ApkVerifier.Builder(apk)
            .setMinCheckedPlatformVersion(MIN_SDK)
            .build()
            .verify()
        check(!result.containsErrors() && result.isVerified) { "APK 未通过整体校验（v3 视角）" }
        check(result.isVerifiedUsingV3Scheme()) { "APK 未通过 v3 签名校验" }
        val cert = result.signerCertificates.firstOrNull()
            ?: throw IllegalStateException("APK 校验通过但无签名证书")
        return CertFingerprints.sha256(cert)
    }

    companion object {
        const val ALIAS = "zhique-release"
        const val MIN_SDK = 31
        const val V2_MIN_SDK = 24
        const val V1_MIN_SDK = 21
    }
}
