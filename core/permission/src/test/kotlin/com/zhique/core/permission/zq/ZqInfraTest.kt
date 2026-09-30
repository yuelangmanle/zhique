package com.zhique.core.permission.zq

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** zq 基础件：沙盒路径、订阅句柄、WAV 封头、参数解析、扫描折叠、传感器映射。 */
class ZqInfraTest {

    // ---- ZqPaths 沙盒 ----

    @Test
    fun `沙盒内相对路径放行`() {
        val base = File("/tmp/base")
        assertEquals(File("/tmp/base/a.txt"), ZqPaths.resolveInSandbox(base, "a.txt"))
        assertEquals(File("/tmp/base/sub/b.txt"), ZqPaths.resolveInSandbox(base, "sub/b.txt"))
        // sub/../ 折叠后仍在沙盒内
        assertEquals(File("/tmp/base/ok.txt").canonicalPath, ZqPaths.resolveInSandbox(base, "sub/../ok.txt").canonicalPath)
    }

    @Test
    fun `越界路径一律拒绝`() {
        val base = File("/tmp/base")
        assertFailsWith<IllegalArgumentException> { ZqPaths.resolveInSandbox(base, "") }
        assertFailsWith<IllegalArgumentException> { ZqPaths.resolveInSandbox(base, "   ") }
        assertFailsWith<IllegalArgumentException> { ZqPaths.resolveInSandbox(base, "/etc/passwd") }
        assertFailsWith<IllegalArgumentException> { ZqPaths.resolveInSandbox(base, "../escape.txt") }
        assertFailsWith<IllegalArgumentException> { ZqPaths.resolveInSandbox(base, "a/../../escape.txt") }
    }

    // ---- Subscriptions ----

    @Test
    fun `订阅句柄生命周期`() {
        val subs = Subscriptions()
        val s1 = subs.new()
        val s2 = subs.new()
        assertTrue(s1 != s2, "句柄唯一")
        assertTrue(subs.isActive(s1) && subs.isActive(s2))
        assertEquals(2, subs.count())
        assertTrue(subs.cancel(s1), "首次取消成功")
        assertFalse(subs.cancel(s1), "重复取消返回 false")
        assertFalse(subs.isActive(s1))
        assertTrue(subs.isActive(s2))
        assertFalse(subs.isActive("不存在的句柄"))
    }

    // ---- Wav 封头 ----

    @Test
    fun `WAV封头44字节且字段正确`() {
        val pcm = ByteArray(1000)
        val wav = Wav.fromPcm(pcm, sampleRate = 44_100, channels = 1)
        assertEquals(1044, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("fmt ", String(wav, 12, 4))
        assertEquals("data", String(wav, 36, 4))
        // RIFF 块大小 = 36 + pcm
        assertEquals(36 + 1000, wav.readIntLe(4))
        // 采样率 LE
        assertEquals(44_100, wav.readIntLe(24))
        // byte rate = 44100 * 1ch * 2bytes
        assertEquals(88_200, wav.readIntLe(28))
        assertEquals(1, wav[20].toInt() and 0xFF, "audioFormat=PCM")
        assertEquals(16, wav[34].toInt() and 0xFF, "bitsPerSample")
        assertEquals(1000, wav.readIntLe(40), "data 块大小 = pcm")
    }

    private fun ByteArray.readIntLe(at: Int): Int =
        (this[at].toInt() and 0xFF) or
            ((this[at + 1].toInt() and 0xFF) shl 8) or
            ((this[at + 2].toInt() and 0xFF) shl 16) or
            ((this[at + 3].toInt() and 0xFF) shl 24)

    // ---- ZqArgs ----

    @Test
    fun `参数解析_缺省空对象`() {
        assertEquals(0, ZqArgs.parse(null).size)
        assertEquals(0, ZqArgs.firstObject("[]").size)
        assertEquals(0, ZqArgs.firstObject(null as String?).size)
    }

    @Test
    fun `参数解析_取首个对象`() {
        val obj = ZqArgs.firstObject("""[{"a":"x","n":3,"f":true},{"b":1}]""")
        assertEquals("x", ZqArgs.optText(obj, "a"))
        assertEquals(3, ZqArgs.optInt(obj, "n", 0, 0, 100))
        assertEquals(true, ZqArgs.optBool(obj, "f"))
    }

    @Test
    fun `必填参数缺失即抛`() {
        val obj = JsonObject(emptyMap())
        assertFailsWith<IllegalArgumentException> { ZqArgs.text(obj, "path") }
    }

    @Test
    fun `整数参数夹界`() {
        val obj = Json.parseToJsonElement("""[{"v":9999,"w":-5}]""").jsonArray.let { ZqArgs.firstObject(it) }
        assertEquals(100, ZqArgs.optInt(obj, "v", 10, 1, 100))
        assertEquals(1, ZqArgs.optInt(obj, "w", 10, 1, 100))
        assertEquals(10, ZqArgs.optInt(obj, "absent", 10, 1, 100))
    }

    @Test
    fun `坏JSON解析抛错由调度层收口`() {
        assertFailsWith<Exception> { ZqArgs.parse("{broken") }
    }

    // ---- ScanFolder 蓝牙折叠 ----

    @Test
    fun `扫描结果同址折叠取最强RSSI`() {
        val folder = ScanFolder()
        folder.add("AA", "设备A", -70)
        folder.add("AA", null, -50)
        folder.add("BB", "设备B", -60)
        folder.add("CC", null, -40)
        folder.add("AA", "旧名", -90) // 更弱：不覆盖名称与 RSSI
        val snap = folder.snapshot()
        assertEquals(3, snap.size)
        assertEquals(listOf("CC", "AA", "BB"), snap.map { it.address }, "按 RSSI 强到弱")
        assertEquals(-50, snap.first { it.address == "AA" }.rssi)
        assertEquals("设备A", snap.first { it.address == "AA" }.name, "弱信号不覆盖已有名称")
        assertEquals("设备B", snap.first { it.address == "BB" }.name)
    }

    // ---- SensorSpec 传感器映射 ----

    @Test
    fun `传感器类型映射`() {
        assertEquals(android.hardware.Sensor.TYPE_ACCELEROMETER, ZqSensor.Spec.parse("accel", null).sensorType)
        assertEquals(android.hardware.Sensor.TYPE_GYROSCOPE, ZqSensor.Spec.parse("gyro", null).sensorType)
        assertEquals(android.hardware.Sensor.TYPE_MAGNETIC_FIELD, ZqSensor.Spec.parse("magnet", null).sensorType)
        assertFailsWith<IllegalArgumentException> { ZqSensor.Spec.parse("telepathy", null) }
    }

    @Test
    fun `传感器频率映射与默认`() {
        assertEquals(android.hardware.SensorManager.SENSOR_DELAY_GAME, ZqSensor.Spec.parse("accel", "game").delay)
        assertEquals(android.hardware.SensorManager.SENSOR_DELAY_NORMAL, ZqSensor.Spec.parse("accel", "normal").delay)
        assertEquals(android.hardware.SensorManager.SENSOR_DELAY_UI, ZqSensor.Spec.parse("gyro", null).delay, "缺省 UI 档")
    }

    // ---- ZqEvents 推送协议 ----

    @Test
    fun `事件推送JS含转义`() {
        assertEquals(
            "window.__zqEvent && __zqEvent(\"s1\", \"{\\\"v\\\":1}\")",
            ZqEvents.pushJs("s1", "{\"v\":1}"),
        )
        assertTrue(ZqEvents.pushJs("s1", "a\"b\nc").contains("a\\\"b\\nc"), "引号与换行必须转义")
    }
}

/** 质量审查 Minor：转义补全、8MB 上限、订阅全量关停。 */
class ZqMinorHardeningTest {

    @Test
    fun `转义包含r与行分隔符`() {
        assertEquals(
            "window.__zqEvent && __zqEvent(\"s\", \"a\\rb\\u2028c\")",
            ZqEvents.pushJs("s", "a\rb\u2028c"),
        )
    }

    @Test
    fun `Subscriptions_cancelAll全量关停`() {
        val subs = Subscriptions()
        subs.new(); subs.new(); subs.new()
        assertEquals(3, subs.cancelAll())
        assertEquals(0, subs.count())
        assertEquals(0, subs.cancelAll(), "重复关停幂等")
    }

    @Test
    fun `ZqFile_read超出8MB回too large`() = kotlinx.coroutines.test.runTest {
        val dir = org.junit.rules.TemporaryFolder().apply { create() }
        val big = File(dir.root, "big.txt")
        java.io.RandomAccessFile(big, "rw").use { it.setLength(ZqLimits.MAX_INLINE_BYTES + 1) } // 稀疏文件
        val repo = com.zhique.core.project.ProjectRepository(dir.root)
        val pid = repo.create("大小项目", "<p></p>").id
        val env = ZqEnv(
            pid, dir.root,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            com.zhique.core.permission.PermissionRegistry(repo), {},
        )
        val args = kotlinx.serialization.json.buildJsonObject { put("path", "big.txt") }
        val err = kotlin.runCatching { ZqFile().call("read", args, env) }.exceptionOrNull()
        kotlin.test.assertEquals("too large", err?.message, "超出 8MB 必须回 rejected too large")
        dir.delete()
    }

    @Test
    fun `ZqFile_read小文件正常`() = kotlinx.coroutines.test.runTest {
        val dir = org.junit.rules.TemporaryFolder().apply { create() }
        File(dir.root, "ok.txt").writeText("hello")
        val repo = com.zhique.core.project.ProjectRepository(dir.root)
        val pid = repo.create("小文件项目", "<p></p>").id
        val env = ZqEnv(
            pid, dir.root,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            com.zhique.core.permission.PermissionRegistry(repo), {},
        )
        val args = kotlinx.serialization.json.buildJsonObject { put("path", "ok.txt") }
        val out = ZqFile().call("read", args, env)
        kotlin.test.assertEquals("hello", out.toString().let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject["content"]?.toString().orEmpty().trim('"') })
        dir.delete()
    }
}
