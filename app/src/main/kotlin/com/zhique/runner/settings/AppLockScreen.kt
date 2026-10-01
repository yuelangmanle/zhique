package com.zhique.runner.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

/**
 * 应用锁验证门（规格 §6）：覆盖在 ZhiqueApp 最上层，锁住整个界面。
 * 生物识别可用且开启 → 自动弹 BiometricPrompt（失败/取消回落 PIN）；
 * 否则 PIN 输入验证。连续失败有递增冷却（[AppLockController]）。
 */
@Composable
fun AppLockScreen(
    controller: AppLockController,
    state: AppLockController.State,
) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }

    // 生物识别：进锁屏自动弹一次（可用且开启时）；FragmentActivity 才能挂 Prompt
    LaunchedEffect(state.locked, state.biometricAvailable) {
        if (state.locked && state.biometricAvailable) {
            (context as? FragmentActivity)?.let { activity ->
                authenticateBiometric(activity) { controller.onBiometricSuccess() }
            }
        }
    }

    Surface(
        Modifier.fillMaxSize().testTag("app-lock-screen"),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("织雀已锁定", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.biometricAvailable) "验证指纹/面容，或输入 PIN" else "输入 PIN 解锁",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8) },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("lock-pin-input"),
                )
                if (state.cooldownMs > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "尝试过于频繁，请等待 ${state.cooldownMs / 1000}s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("lock-cooldown"),
                    )
                } else if (state.error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("lock-error"),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        controller.verifyPin(pin)
                        pin = ""
                    },
                    enabled = pin.length >= 4 && state.cooldownMs == 0L,
                    modifier = Modifier.fillMaxWidth().testTag("lock-unlock"),
                ) { Text("解锁") }
                if (state.biometricAvailable) {
                    TextButton(
                        onClick = {
                            (context as? FragmentActivity)?.let { activity ->
                                authenticateBiometric(activity) { controller.onBiometricSuccess() }
                            }
                        },
                        modifier = Modifier.testTag("lock-biometric"),
                    ) { Text("使用生物识别") }
                }
            }
        }
    }
}

/** BiometricPrompt 封装：设备无硬件/未录入时静默回落 PIN（不崩不阻塞）。 */
private fun authenticateBiometric(activity: FragmentActivity, onSuccess: () -> Unit) {
    runCatching {
        val bm = BiometricManager.from(activity)
        if (bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) !=
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            return
        }
        val executor: Executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("解锁织雀")
            .setSubtitle("验证生物识别以继续")
            .setNegativeButtonText("使用 PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        prompt.authenticate(info)
    }
}
