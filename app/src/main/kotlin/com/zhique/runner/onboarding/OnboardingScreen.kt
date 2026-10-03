package com.zhique.runner.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * 首启引导（规格 §5.3 屏 1，X4 90 秒）：
 * 步骤 1 粘贴识别 API 配置 → 预填表单 + 测连通；步骤 2 Apilot 接入（M8 激活：
 * 四档 scope 授权读取，Key 不勾不回传）；
 * 可跳过玩示例；隐私告知卡勾选记录（「代码将发送至你配置的服务商」）。
 */
@Composable
fun OnboardingScreen(
    controller: OnboardingController,
    modifier: Modifier = Modifier,
    apilot: com.zhique.runner.settings.ApilotController? = null,
) {
    val state by controller.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val apilotState: com.zhique.runner.settings.ApilotController.UiState? = apilot?.state?.collectAsState()?.value
    // 稳定 key 显式注册（真机 B7 回归修复：引导页这里仍是旧式 rememberLauncher——
    // 随机 key 累积触发 FragmentActivity "Can only use lower 16 bits" 崩溃）
    val registry = androidx.activity.compose.LocalActivityResultRegistryOwner.current
        ?.activityResultRegistry ?: error("宿主非 ComponentActivity")
    val apilotLauncher = remember {
        registry.register(
            "zhique-onboarding-apilot-pick",
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            apilot?.handleActivityResult(result.resultCode, result.data, context.contentResolver)
        }
    }
    val apilotLauncherLaunch: (android.content.Intent) -> Unit = { intent ->
        runCatching { apilotLauncher.launch(intent) }
            .onFailure { apilot?.setNotice("无法打开 Apilot（${it.message}）") }
    }
    // 网关一键授权（独立稳定 key；回传走 handleGatewayResult 落 Provider）
    val apilotGatewayLauncher = remember {
        registry.register(
            "zhique-onboarding-apilot-gateway",
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            apilot?.handleGatewayResult(result.resultCode, result.data)
        }
    }
    val apilotGatewayLauncherLaunch: (android.content.Intent) -> Unit = { intent ->
        runCatching { apilotGatewayLauncher.launch(intent) }
            .onFailure { apilot?.setNotice("无法打开 Apilot（${it.message}）——网关功能需 Apilot v2.5.0+") }
    }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("onboarding-root"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("欢迎用织雀", style = MaterialTheme.typography.headlineSmall)
            Text("90 秒完成接入：粘贴你的 API 配置，或直接跳过玩内置示例。", color = MaterialTheme.colorScheme.secondary)

            // 步骤 1：粘贴识别 API 配置
            Text("步骤 1 · 接入 AI 服务商", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.paste,
                onValueChange = controller::setPaste,
                label = { Text("粘贴 API 配置（JSON / cURL / Key）") },
                modifier = Modifier.fillMaxWidth().testTag("onb-paste"),
                minLines = 2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = controller::recognize, modifier = Modifier.testTag("onb-recognize")) {
                    Text("识别并预填")
                }
                OutlinedButton(
                    onClick = controller::testConnection,
                    enabled = !state.busy && state.recognized,
                    modifier = Modifier.testTag("onb-test"),
                ) { Text("测连通") }
            }
            if (state.recognized) {
                OutlinedTextField(
                    value = state.baseUrl,
                    onValueChange = { v -> controller.update { it.copy(baseUrl = v) } },
                    label = { Text("Base URL") },
                    modifier = Modifier.fillMaxWidth().testTag("onb-base"),
                )
                OutlinedTextField(
                    value = state.apiKey,
                    onValueChange = { v -> controller.update { it.copy(apiKey = v) } },
                    label = { Text("API Key（密文存储）") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("onb-key"),
                )
                OutlinedTextField(
                    value = state.model,
                    onValueChange = { v -> controller.update { it.copy(model = v) } },
                    label = { Text("默认模型") },
                    modifier = Modifier.fillMaxWidth().testTag("onb-model"),
                )
            }
            state.notice?.let {
                Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag("onb-notice"))
            }

            // 步骤 2：Apilot 接入（M8 §4.9 PICK_API_CONFIG + v2.5.0 GRANT_GATEWAY 一键网关）
            Column(Modifier.fillMaxWidth().testTag("onb-apilot-card")) {
                Text("步骤 2 · 从 Apilot 导入（可选）", style = MaterialTheme.typography.titleMedium)
                Text(
                    "已装 Apilot？一键授权读取 API 配置（Key 不勾不回传）。" +
                        if (apilotState?.installed == true) "已检测到 Apilot。" else "未检测到 Apilot 时此步可跳过。",
                    color = MaterialTheme.colorScheme.secondary,
                )
                OutlinedButton(
                    onClick = { apilot?.let { apilotLauncherLaunch(it.pickIntent()) } },
                    enabled = apilot != null && apilotState?.installed == true,
                    modifier = Modifier.testTag("onb-apilot"),
                ) {
                    Text("从 Apilot 接入")
                }
                // 一键网关（Apilot v2.5.0+）：免选配置，直接拿 baseUrl+model（独立 launcher 走网关回传）
                OutlinedButton(
                    onClick = { apilot?.let { apilotGatewayLauncherLaunch(it.gatewayIntent()) } },
                    enabled = apilot != null && apilotState?.installed == true,
                    modifier = Modifier.testTag("onb-apilot-gateway"),
                ) {
                    Text("⚡ 一键网关接入")
                }
                apilotState?.notice?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.testTag("onb-apilot-notice"),
                    )
                }
            }

            // 隐私告知卡（§6：首次用云 API 前告知）
            Row(
                Modifier
                    .fillMaxWidth()
                    .testTag("onb-privacy-card"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = state.privacyAcked,
                    onCheckedChange = controller::setPrivacyAcked,
                    modifier = Modifier.testTag("onb-privacy-check"),
                )
                Text(
                    "我已知晓：我的代码与请求内容将发送至我配置的服务商处理；" +
                        "API Key 仅加密存储在本机，永不出现在 URL 与日志。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = controller::finish,
                    modifier = Modifier.testTag("onb-finish"),
                ) { Text("完成") }
                OutlinedButton(onClick = controller::skip, modifier = Modifier.testTag("onb-skip")) {
                    Text("跳过，玩示例")
                }
            }
        }
    }
}
