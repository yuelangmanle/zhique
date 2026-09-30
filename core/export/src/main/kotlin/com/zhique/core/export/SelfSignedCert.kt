package com.zhique.core.export

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Calendar

/**
 * 自签名 X.509 证书生成（零第三方依赖，DER 手工编码）：
 * RSA-2048 密钥对 → v3 自签名证书（SHA256withRSA，CN=织雀发布密钥），
 * 供 KeystoreManager 写入 PKCS12 密钥库。纯 JVM 可测；Android 与 JVM
 * 均有 CertificateFactory("X.509")，生成的证书两边一致。
 */

internal object SelfSignedCert {

    private val random = SecureRandom()

    fun generate(keyPair: KeyPair, cn: String, validDays: Long = 3650): X509Certificate {
        val serial = BigInteger(64, random)
        val now = Calendar.getInstance().apply {
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -1) // 时钟偏移容差
        }
        val notBefore = now.time
        now.add(Calendar.DAY_OF_YEAR, validDays.toInt() + 1)
        val notAfter = now.time

        val issuer = name(cn)
        val spki = publicKeyInfo(keyPair)
        val tbs = sequence(
            tagged(0, integer(BigInteger.TWO)),                       // version v3
            integer(serial),
            sequence(oid("1.2.840.113549.1.1.11"), null_()), // sha256WithRSAEncryption
            issuer,
            sequence(utcTime(notBefore), utcTime(notAfter)),
            issuer,                                      // 自签名：subject = issuer
            spki,
        )
        val signer = java.security.Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(tbs)
        val sig = signer.sign()
        val certDer = sequence(tbs, sequence(oid("1.2.840.113549.1.1.11"), null_()), bitString(sig))
        return java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(certDer.inputStream()) as X509Certificate
    }

    /** 轻量 X.500 Name：仅 CN 一个 RDN（Name = SEQ(SET(SEQ(oid, value)))）。 */
    private fun name(cn: String): ByteArray = sequence(
        set( // RelativeDistinguishedName = SET
            sequence(oid("2.5.4.3"), utf8(cn)), // AttributeTypeAndValue
        ),
    )

    private fun publicKeyInfo(keyPair: KeyPair): ByteArray {
        val rsa = keyPair.public as RSAPublicKey
        val rsaKey = sequence(
            integer(rsa.modulus),
            integer(rsa.publicExponent),
        )
        return sequence(
            sequence(oid("1.2.840.113549.1.1.1"), null_()),
            bitString(rsaKey),
        )
    }

    // ---- DER 编码原语 ----

    private fun sequence(vararg parts: ByteArray): ByteArray = tlv(0x30, parts.concat())
    private fun set(vararg parts: ByteArray): ByteArray = tlv(0x31, parts.concat())
    private fun integer(v: BigInteger): ByteArray = tlv(0x02, v.toByteArray())
    private fun utf8(s: String): ByteArray = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
    private fun null_(): ByteArray = byteArrayOf(0x05, 0x00)
    private fun bitString(data: ByteArray): ByteArray = tlv(0x03, byteArrayOf(0) + data)
    private fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toInt() }
        val out = ByteArrayOutputStream()
        out.write(parts[0] * 40 + parts[1])
        for (arc in parts.drop(2)) {
            var v = arc
            val groups = ArrayList<Int>()
            do {
                groups.add(v and 0x7F) // 低 7 位先入列；发射时倒序（高位在前）
                v = v shr 7
            } while (v > 0)
            for (i in groups.indices.reversed()) {
                val b = groups[i]
                // 除最后一个（最低 7 位）外都要续位标志 0x80
                out.write(if (i == 0) b else b or 0x80)
            }
        }
        return tlv(0x06, out.toByteArray())
    }

    private fun utcTime(t: java.util.Date): ByteArray {
        val cal = Calendar.getInstance().apply { time = t }
        fun two(v: Int) = v.toString().padStart(2, '0')
        // UTCTime YYMMDDHHMMSSZ（无毫秒）
        val s = "%02d%02d%02d%02d%02d%02dZ".format(
            cal.get(Calendar.YEAR) % 100,
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            cal.get(Calendar.SECOND),
        )
        return tlv(0x17, s.toByteArray(Charsets.US_ASCII))
    }

    private fun tagged(tag: Int, body: ByteArray): ByteArray {
        val header = ByteArrayOutputStream()
        header.write(0xA0 or tag)
        writeLen(header, body.size)
        return header.toByteArray() + body
    }

    private fun tlv(tag: Int, body: ByteArray): ByteArray {
        val header = ByteArrayOutputStream()
        header.write(tag)
        writeLen(header, body.size)
        return header.toByteArray() + body
    }

    private fun writeLen(out: ByteArrayOutputStream, len: Int) {
        if (len < 0x80) {
            out.write(len)
        } else {
            var v = len
            var n = 0
            while (v > 0) {
                n++
                v = v shr 8
            }
            out.write(0x80 or n)
            v = len
            val bytes = ByteArray(n)
            for (i in n - 1 downTo 0) {
                bytes[i] = (v and 0xFF).toByte()
                v = v shr 8
            }
            bytes.forEach { out.write(it.toInt()) }
        }
    }

    private fun Array<out ByteArray>.concat(): ByteArray {
        val out = ByteArrayOutputStream()
        forEach { out.write(it) }
        return out.toByteArray()
    }
}
