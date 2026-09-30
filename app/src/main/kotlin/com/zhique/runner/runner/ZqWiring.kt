package com.zhique.runner.runner

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import com.zhique.core.permission.PermissionRegistry
import com.zhique.core.permission.zq.ProjectionGateway
import com.zhique.core.permission.zq.SafGateway
import com.zhique.core.permission.zq.ZqBluetooth
import com.zhique.core.permission.zq.ZqCamera
import com.zhique.core.permission.zq.ZqClipboard
import com.zhique.core.permission.zq.ZqDispatcher
import com.zhique.core.permission.zq.ZqEnv
import com.zhique.core.permission.zq.ZqFile
import com.zhique.core.permission.zq.ZqLocation
import com.zhique.core.permission.zq.ZqMic
import com.zhique.core.permission.zq.ZqNotify
import com.zhique.core.permission.zq.ZqScreen
import com.zhique.core.permission.zq.ZqSensor
import com.zhique.core.permission.zq.ZqShare
import com.zhique.core.permission.zq.ZqW3CRouter
import com.zhique.core.project.ProjectMeta
import com.zhique.core.web.PermissionGateway
import com.zhique.core.web.WebViewHost
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * zq 全能力桥接线（M5）：把 [ZqDispatcher] 与十个能力挂到运行器宿主，
 * 并注入 W3C 权限网关（getUserMedia → 同一注册表矩阵）。
 */
object ZqWiring {

    fun install(
        project: ProjectMeta,
        projectDir: File,
        context: Context,
        host: WebViewHost,
        scope: CoroutineScope,
        registry: PermissionRegistry,
    ): ZqDispatcher {
        val activity = context as? ComponentActivity
        val env = ZqEnv(
            projectId = project.id,
            projectDir = projectDir,
            scope = scope,
            registry = registry,
            evaluateJs = host::evaluate,
            appContext = context.applicationContext,
            activity = context as? Activity,
            saf = activity?.let { ActivitySafGateway(it) },
            projection = activity?.let { ActivityProjectionGateway(it) },
        )
        val dispatcher = ZqDispatcher(env)
        dispatcher.register(ZqCamera())
        dispatcher.register(ZqMic())
        dispatcher.register(ZqFile())
        dispatcher.register(ZqLocation())
        dispatcher.register(ZqSensor())
        dispatcher.register(ZqBluetooth())
        dispatcher.register(ZqNotify())
        dispatcher.register(ZqClipboard())
        dispatcher.register(ZqShare())
        dispatcher.register(ZqScreen())
        dispatcher.attachTo(host.zqRouter)
        val w3c = ZqW3CRouter(registry, project.id, scope)
        host.permissionGateway = PermissionGateway { request ->
            val resources = request.resources ?: emptyArray()
            w3c.onRequest(
                resources.toList(),
                grant = { request.grant(request.resources ?: resources) },
                deny = { request.deny() },
            )
        }
        return dispatcher
    }
}

/** SAF pick/save 网关：Activity Result Registry 直挂（运行中页面必然 STARTED）。 */
class ActivitySafGateway(private val activity: ComponentActivity) : SafGateway {

    private val seq = java.util.concurrent.atomic.AtomicInteger()

    private suspend fun <I, O> launch(
        contract: ActivityResultContract<I, O>,
        input: I,
        key: String,
    ): O? = suspendCancellableCoroutine { cont ->
        val launcher = activity.activityResultRegistry.register(key, contract) { result ->
            cont.resume(result)
        }
        cont.invokeOnCancellation { launcher.unregister() }
        launcher.launch(input)
    }

    override suspend fun pick(mime: String?): JsonElement {
        val uri = launch(
            ActivityResultContracts.OpenDocument(),
            arrayOf(mime ?: "*/*"),
            "zq-saf-pick-${seq.incrementAndGet()}",
        )
        if (uri == null) return buildJsonObject { put("code", "canceled") }
        // 立即读、不存 URI（规格 §4.4 同款纪律）
        val content = runCatching {
            activity.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        return buildJsonObject {
            put("path", uri.toString())
            put("content", content ?: "")
        }
    }

    override suspend fun save(name: String, mime: String?, content: String): JsonElement {
        val uri = launch(
            ActivityResultContracts.CreateDocument(mime ?: "text/plain"),
            name,
            "zq-saf-save-${seq.incrementAndGet()}",
        )
        if (uri == null) return buildJsonObject { put("code", "canceled") }
        runCatching {
            activity.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
        }
        return buildJsonObject { put("path", uri.toString()); put("bytes", content.toByteArray().size) }
    }
}

/** MediaProjection 网关：createScreenCaptureIntent 弹系统投影授权（独立授权）。 */
class ActivityProjectionGateway(private val activity: ComponentActivity) : ProjectionGateway {

    private val seq = java.util.concurrent.atomic.AtomicInteger()

    override suspend fun projection(): android.media.projection.MediaProjection? {
        val mpm = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as? android.media.projection.MediaProjectionManager ?: return null
        val result = suspendCancellableCoroutine<Pair<Int, android.content.Intent?>?> { cont ->
            val contract = object : ActivityResultContract<Unit, Pair<Int, android.content.Intent?>?>() {
                override fun createIntent(context: Context, input: Unit): android.content.Intent =
                    mpm.createScreenCaptureIntent()

                override fun parseResult(resultCode: Int, intent: android.content.Intent?): Pair<Int, android.content.Intent?>? =
                    resultCode to intent
            }
            val launcher = activity.activityResultRegistry.register(
                "zq-projection-${seq.incrementAndGet()}",
                contract,
            ) { result -> cont.resume(result) }
            cont.invokeOnCancellation { launcher.unregister() }
            launcher.launch(Unit)
        } ?: return null
        val (code, data) = result
        if (code != android.app.Activity.RESULT_OK || data == null) return null
        return mpm.getMediaProjection(code, data)
    }
}
