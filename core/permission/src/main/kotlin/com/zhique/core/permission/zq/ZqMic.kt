package com.zhique.core.permission.zq

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.zhique.core.permission.Capability
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * zq.mic：AudioRecord 录制 → WAV 存项目目录 audio/（计划 Task 5.2）。
 * WAV 封头为纯函数（[Wav.fromPcm]），可 JVM 测试；真机录音验收留 M10。
 */
class ZqMic : ZqCapability {

    override val ns = "mic"
    override val required = Capability.MIC
    override val methods = listOf("record")

    override fun why(fn: String) = "录音并把音频存入项目目录"

    @SuppressLint("MissingPermission") // 下方已显式检查系统权限
    override suspend fun call(fn: String, args: JsonObject, env: ZqEnv): JsonElement {
        require(fn == "record") { "zq.mic 未知方法: $fn" }
        val context = env.appContext ?: throw IllegalStateException("无宿主环境")
        if (!SystemPerms.granted(context, android.Manifest.permission.RECORD_AUDIO)) {
            throw IllegalStateException("系统麦克风权限未授予（可重新发起授权，或在系统设置中开启织雀的麦克风）")
        }
        val seconds = args.zqOptInt("seconds", DEFAULT_SECONDS, MIN_SECONDS, MAX_SECONDS)
        val dir = File(env.projectDir, "audio").apply { mkdirs() }
        val out = File(dir, "zq-${System.currentTimeMillis()}.wav")

        val pcm = withContext(Dispatchers.IO) { recordPcm(seconds) { isActive } }
        out.writeBytes(Wav.fromPcm(pcm, SAMPLE_RATE))
        return buildJsonObject {
            put("path", out.relativeTo(env.projectDir).path)
            put("seconds", seconds)
            put("bytes", out.length())
        }
    }

    /** 阻塞录制定长 PCM（IO 线程）；每轮检查协程取消（审查修复 Minor #6），取消即停写。 */
    private fun recordPcm(seconds: Int, shouldContinue: () -> Boolean): ByteArray {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val buffer = ByteArrayOutputStream()
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, 4096),
        )
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("麦克风不可用（可能被其他应用占用）")
            }
            recorder.startRecording()
            val chunk = ByteArray(2048)
            val deadline = System.currentTimeMillis() + seconds * 1000L
            while (System.currentTimeMillis() < deadline && shouldContinue()) {
                val n = recorder.read(chunk, 0, chunk.size)
                if (n > 0) buffer.write(chunk, 0, n)
            }
            recorder.stop()
        } finally {
            recorder.release()
        }
        return buffer.toByteArray()
    }

    companion object {
        const val SAMPLE_RATE = 44_100
        const val DEFAULT_SECONDS = 5
        const val MIN_SECONDS = 1
        const val MAX_SECONDS = 60
    }
}

/** 16-bit PCM mono → 完整 WAV（44 字节 RIFF 头；纯函数，JVM 测试覆盖）。 */
object Wav {
    fun fromPcm(pcm: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray {
        val out = ByteArray(44 + pcm.size)
        ascii(out, 0, "RIFF")
        leInt(out, 4, 36 + pcm.size)
        ascii(out, 8, "WAVE")
        ascii(out, 12, "fmt ")
        leInt(out, 16, 16) // PCM chunk size
        out[20] = 1 // audio format = PCM
        out[21] = 0
        out[22] = channels.toByte()
        out[23] = 0
        leInt(out, 24, sampleRate)
        leInt(out, 28, sampleRate * channels * 2) // byte rate
        out[32] = (channels * 2).toByte() // block align
        out[33] = 0
        out[34] = 16 // bits per sample
        out[35] = 0
        ascii(out, 36, "data")
        leInt(out, 40, pcm.size)
        pcm.copyInto(out, 44)
        return out
    }

    private fun ascii(dst: ByteArray, at: Int, s: String) {
        for (i in s.indices) dst[at + i] = s[i].code.toByte()
    }

    private fun leInt(dst: ByteArray, at: Int, v: Int) {
        dst[at] = (v and 0xFF).toByte()
        dst[at + 1] = ((v shr 8) and 0xFF).toByte()
        dst[at + 2] = ((v shr 16) and 0xFF).toByte()
        dst[at + 3] = ((v shr 24) and 0xFF).toByte()
    }
}
