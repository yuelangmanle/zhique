package com.zhique.core.permission.zq

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import com.zhique.core.permission.Capability
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * zq.bluetooth：BLE 扫描（计划 Task 5.2）。
 * `scan({seconds?})` 聚合窗口内的设备（同址取最强 RSSI），返回设备清单；
 * `stopScan()` 提前终止。清单折叠逻辑为纯函数（[ScanFolder]）可 JVM 测试。
 */
class ZqBluetooth : ZqCapability {

    override val ns = "bluetooth"
    override val required = Capability.BLUETOOTH
    override val methods = listOf("scan", "stopScan")

    override fun why(fn: String): String = when (fn) {
        "scan" -> "扫描附近的 BLE 设备（约数秒）"
        else -> "停止 BLE 扫描"
    }

    @Volatile
    private var activeCallback: ScanCallback? = null

    override suspend fun call(fn: String, args: JsonObject, env: ZqEnv): JsonElement {
        val context = env.appContext ?: throw IllegalStateException("无宿主环境")
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        return when (fn) {
            "scan" -> {
                if (!SystemPerms.granted(
                        context,
                        android.Manifest.permission.BLUETOOTH_SCAN,
                        android.Manifest.permission.BLUETOOTH_CONNECT,
                    )
                ) {
                    throw IllegalStateException("系统蓝牙权限未授予（可重新发起授权，或在系统设置中开启织雀的附近设备）")
                }
                val scanner = adapter?.bluetoothLeScanner
                    ?: throw IllegalStateException("蓝牙不可用（未开启或设备不支持 BLE）")
                val seconds = args.zqOptInt("seconds", DEFAULT_SECONDS, MIN_SECONDS, MAX_SECONDS)
                val folder = ScanFolder()
                val callback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        folder.add(result.device?.address ?: return, result.device?.name, result.rssi)
                    }
                }
                activeCallback = callback
                withContext(Dispatchers.Main) { scanner.startScan(callback) }
                try {
                    delay(seconds * 1000L)
                } finally {
                    withContext(Dispatchers.Main) {
                        runCatching { scanner.stopScan(callback) }
                    }
                    activeCallback = null
                }
                buildJsonObject {
                    put("devices", JsonArray(folder.snapshot().map { d ->
                        buildJsonObject {
                            put("address", d.address)
                            put("name", d.name ?: "")
                            put("rssi", d.rssi)
                        }
                    }))
                }
            }
            "stopScan" -> {
                val cb = activeCallback
                activeCallback = null
                if (cb != null) {
                    withContext(Dispatchers.Main) {
                        runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
                    }
                }
                buildJsonObject { put("ok", cb != null) }
            }
            else -> throw IllegalArgumentException("zq.bluetooth 未知方法: $fn")
        }
    }

    companion object {
        const val DEFAULT_SECONDS = 5
        const val MIN_SECONDS = 1
        const val MAX_SECONDS = 30
    }
}

/** 扫描结果折叠：同地址取最强 RSSI 与最近名称（纯逻辑）。 */
class ScanFolder {
    data class Device(val address: String, val name: String?, val rssi: Int)

    private val lock = Any()
    private val byAddress = HashMap<String, Device>()

    fun add(address: String, name: String?, rssi: Int) {
        synchronized(lock) {
            val old = byAddress[address]
            if (old == null || rssi > old.rssi) {
                byAddress[address] = Device(address, name ?: old?.name, rssi)
            } else if (name != null && old.name == null) {
                byAddress[address] = old.copy(name = name)
            }
        }
    }

    fun snapshot(): List<Device> = synchronized(lock) {
        byAddress.values.sortedByDescending { it.rssi }
    }

    fun size(): Int = synchronized(lock) { byAddress.size }
}
