package com.zhique.core.export

import com.zhique.core.common.crypto.CryptoStore
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 备份状态（决策29 第 3 条：电脑侧备份为一等公民，导出中心/权限中心常装）。 */
data class BackupStatus(
    val keystoreExists: Boolean,
    val certSha256: String?,
    val lastBackupAt: Long,
    val backupDue: Boolean,
)

/** 导入恢复结果。 */
sealed class ImportResult {
    /** 导入生效（指纹与既有导出记录一致，或尚无导出记录）。 */
    data class Accepted(val certSha256: String) : ImportResult()

    /** 拒绝生效：证书指纹与既有 ExportRecord 不一致——导入的密钥库签不出历史版本。 */
    data class Rejected(val certSha256: String, val expected: List<String>) : ImportResult()
}

/** 导入的 .jks 无法读取（口令错/文件坏）。 */
class KeystoreImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 统一签名密钥库（M6 Task 6.2 · 决策29 逐条）：
 *
 * 1. **一次生成、永久复用**——首启生成 RSA-2048 自签名证书，持久到
 *    `keystore/zhique-release.jks`，之后所有导出复用同一密钥；
 * 2. **口令零字面量**——口令运行时随机生成（SecureRandom 32B → Base64url），
 *    经主密钥（Android Keystore 安全区）AES 密文存盘；源码/配置/测试一律无凭据字面量；
 * 3. **电脑侧备份**——[exportTo] 产出 .jks 副本（分享/SAF/USB 由 UI 层接力），
 *    [markBackedUp] 记录 `lastBackupAt`，[backupStatus] 给出超期判定（默认 30 天）；
 * 4. **恢复**——[import]（.jks + 口令），导入后证书 SHA-256 与既有 ExportRecord
 *    的 certSha256 比对，不一致拒绝生效；
 * 5. 与 [SignatureGuard] 配合：导出前 PackageManager 比对已装包签名，杜绝更新装不上。
 *
 * 存储格式为 PKCS12（JKS 的现代同构格式、keytool 现行默认，Android/JVM 通吃），
 * 文件名保持规格的 `zhique-release.jks`；导入端两种格式都认。
 */
class KeystoreManager(
    rootDir: File,
    private val crypto: CryptoStore,
    private val now: () -> Long = System::currentTimeMillis,
    backupPeriodDays: Int = DEFAULT_BACKUP_PERIOD_DAYS,
) {

    private val keystoreDir = File(rootDir, KEYSTORE_DIR)
    private val keystoreFile = File(keystoreDir, KEYSTORE_FILE)
    private val secretFile = File(keystoreDir, PASSWORD_FILE)
    private val backupFile = File(keystoreDir, BACKUP_FILE)
    private val backupPeriodMs = backupPeriodDays * 24L * 60 * 60 * 1000
    private val json = Json { ignoreUnknownKeys = true }

    /** 生成/口令串行的锁：并发导出双击不竞写 .tmp（质量审查 Important-1）。 */
    private val lock = Any()

    val file: File get() = keystoreFile

    fun exists(): Boolean = keystoreFile.isFile && secretFile.isFile

    /**
     * 确保密钥库就绪：缺失则生成（RSA-2048 + 自签名证书 → 密钥库），
     * 口令随机生成密文落盘。返回密钥库文件。
     */
    fun ensureKeystore(): File {
        if (exists()) return keystoreFile
        synchronized(lock) {
            if (exists()) return keystoreFile // 双检：等锁期间别线程已完成
            keystoreDir.mkdirs()
            val pass = passwordLocked()
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val cert = SelfSignedCert.generate(pair, CERT_CN)
            val ks = KeyStore.getInstance(STORE_TYPE)
            ks.load(null, null)
            ks.setKeyEntry(KEY_ALIAS, pair.private, pass.toCharArray(), arrayOf(cert))
            val tmp = File(keystoreDir, "${KEYSTORE_FILE}.tmp")
            tmp.outputStream().use { ks.store(it, pass.toCharArray()) }
            if (!tmp.renameTo(keystoreFile)) {
                tmp.delete()
                throw IllegalStateException("密钥库写入失败（.tmp 替换被拒绝）")
            }
            return keystoreFile
        }
    }

    /**
     * 密钥库口令：运行时随机生成、密文持久。首次调用即生成并落盘；
     * 之后解密复用。口令不以任何字面量形式出现在源码/配置/测试。
     */
    fun password(): String = synchronized(lock) { passwordLocked() }

    /** 须持 [lock] 调用。 */
    private fun passwordLocked(): String {
        secretFile.takeIf { it.isFile }?.let {
            return crypto.decrypt(it.readText())
        }
        val bytes = ByteArray(PASSWORD_BYTES)
        SecureRandom().nextBytes(bytes)
        val pass = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        keystoreDir.mkdirs()
        secretFile.writeText(crypto.encrypt(pass))
        return pass
    }

    /** 当前证书（密钥库未就绪时 null）。 */
    fun certificate(): X509Certificate? = loadKeystore()?.getCertificateChain(KEY_ALIAS)?.firstOrNull() as? X509Certificate

    /** 当前证书 SHA-256（hex 小写，64 位；未就绪时 null）。 */
    fun certificateSha256(): String? = certificate()?.let { CertFingerprints.sha256(it) }

    /** 取私钥 + 证书给 Signer（密钥库未就绪时先 [ensureKeystore]）。 */
    fun signingKey(): Signer.Key {
        ensureKeystore()
        val ks = loadKeystore() ?: throw IllegalStateException("keystore unreadable")
        val key = ks.getKey(KEY_ALIAS, password().toCharArray()) as? java.security.PrivateKey
            ?: throw IllegalStateException("keystore missing key entry")
        val cert = ks.getCertificateChain(KEY_ALIAS).firstOrNull() as? X509Certificate
            ?: throw IllegalStateException("keystore missing certificate")
        return Signer.Key(key, cert)
    }

    // ---- 决策29-3：电脑侧备份 ----

    /** 产出 .jks 备份副本到 [target]（UI 层随后分享/SAF 保存到电脑）。返回副本文件。 */
    fun exportTo(target: File): File {
        ensureKeystore() // 未生成则即时生成（首导出前备份也合法）
        target.parentFile?.mkdirs()
        keystoreFile.copyTo(target, overwrite = true)
        return target
    }

    /** 备份完成即记录（备份动作落地的唯一时点）。 */
    fun markBackedUp(at: Long = now()) {
        keystoreDir.mkdirs()
        backupFile.writeText(json.encodeToString(BackupState.serializer(), BackupState(lastBackupAt = at)))
    }

    /** 备份状态：是否就绪、指纹、上次备份时间、是否超期。 */
    fun backupStatus(): BackupStatus {
        val state = runCatching {
            json.decodeFromString(BackupState.serializer(), backupFile.readText())
        }.getOrNull()
        val last = state?.lastBackupAt ?: 0L
        return BackupStatus(
            keystoreExists = exists(),
            certSha256 = certificateSha256(),
            lastBackupAt = last,
            backupDue = exists() && (last == 0L || now() - last > backupPeriodMs),
        )
    }

    // ---- 决策29-4：导入恢复 ----

    /**
     * 导入 .jks/.p12 + 口令还原密钥库。
     * **指纹校验**：[existingFingerprints] 为既有 ExportRecord.certSha256 清单；
     * 非空且导入证书指纹不在其中 → [ImportResult.Rejected]，密钥库保持原状。
     */
    fun import(path: String, pass: String, existingFingerprints: List<String> = emptyList()): ImportResult {
        val src = File(path)
        require(src.isFile) { "keystore file not found: $path" }
        val ks = readForeignKeystore(src, pass)
        // 必须含可用私钥（仅证书的密钥库签不了包，直接拒绝）
        val hasKey = ks.aliases().toList().any { alias ->
            runCatching { ks.isKeyEntry(alias) }.getOrDefault(false) ||
                runCatching { ks.getKey(alias, pass.toCharArray()) != null }.getOrDefault(false)
        }
        if (!hasKey) throw KeystoreImportException("导入文件缺少私钥（仅证书的密钥库无法用于签名）")
        val cert = ks.getCertificateChain(KEY_ALIAS)?.firstOrNull() as? X509Certificate
            ?: ks.aliases().toList().firstOrNull()?.let { ks.getCertificate(it) as? X509Certificate }
            ?: throw KeystoreImportException("导入文件中没有可用证书")
        val fp = CertFingerprints.sha256(cert)
        if (existingFingerprints.isNotEmpty() && fp !in existingFingerprints) {
            return ImportResult.Rejected(fp, existingFingerprints)
        }
        // 先把两份 tmp 全部就绪，再带检查地原子替换（质量审查 Minor-4）
        keystoreDir.mkdirs()
        val tmp = File(keystoreDir, "${KEYSTORE_FILE}.tmp")
        val tmpSecret = File(keystoreDir, "${PASSWORD_FILE}.tmp")
        src.copyTo(tmp, overwrite = true)
        // 口令按导入值重存（密文），保证 signingKey() 可直接解出
        tmpSecret.writeText(crypto.encrypt(pass))
        if (!tmp.renameTo(keystoreFile) || !tmpSecret.renameTo(secretFile)) {
            tmp.delete(); tmpSecret.delete()
            throw KeystoreImportException("密钥库替换失败（.tmp 改名被拒绝）")
        }
        return ImportResult.Accepted(fp)
    }

    /** 兼容读取外部密钥库（PKCS12 优先，退化 JKS）。 */
    private fun readForeignKeystore(src: File, pass: String): KeyStore {
        val chars = pass.toCharArray()
        for (type in listOf(STORE_TYPE, FALLBACK_STORE_TYPE)) {
            try {
                val ks = KeyStore.getInstance(type)
                src.inputStream().use { ks.load(it, chars) }
                if (ks.aliases().hasMoreElements()) return ks
            } catch (_: Exception) {
                // 换下一种格式重试
            }
        }
        throw KeystoreImportException("密钥库读取失败（口令错误或文件损坏）")
    }

    private fun loadKeystore(): KeyStore? {
        if (!keystoreFile.isFile || !secretFile.isFile) return null
        return runCatching {
            KeyStore.getInstance(STORE_TYPE).also { ks ->
                keystoreFile.inputStream().use { ks.load(it, password().toCharArray()) }
            }
        }.getOrNull()
    }

    @Serializable
    private data class BackupState(val lastBackupAt: Long = 0)

    companion object {
        const val KEYSTORE_DIR = "keystore"
        const val KEYSTORE_FILE = "zhique-release.jks"
        const val KEY_ALIAS = "zhique-release"
        const val DEFAULT_BACKUP_PERIOD_DAYS = 30
        const val CERT_CN = "zhique-release"

        private const val PASSWORD_FILE = "secret.bin"
        private const val BACKUP_FILE = "backup.json"
        private const val STORE_TYPE = "PKCS12"
        private const val FALLBACK_STORE_TYPE = "JKS"
        private const val PASSWORD_BYTES = 32
    }
}

/** 证书指纹工具（SHA-256 hex 小写）。 */
object CertFingerprints {
    fun sha256(cert: X509Certificate): String = sha256(cert.encoded)

    fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
