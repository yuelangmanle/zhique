package com.zhique.core.web

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File

/**
 * 独立 :web 进程的 WebView 运行时壳（manifest 声明 android:process=":web"）。
 * 全屏承载 [WebViewHost]；渲染进程反复崩溃时显示降级提示。
 *
 * **M10 取舍记录（M1 偏差④收口）：本体运行器暂不切换到本壳，仍由 :app 主进程
 * [com.zhique.core.web.WebViewHost] 承载，宿主级进程隔离顺延为 v0.2 项。**
 * 理由（稳定性优先，不为切而切）：
 * 1. 现有集成为进程内直连——AgentBridge（evaluateJs/PixelCopy 截图/事件缓冲）、
 *    ZqWiring 十能力（Activity Result、相机 Compose 取景浮层、截屏前台服务）、
 *    W3C 权限网关（授权卡往返）全部绑定主进程；切到 :web 需为事件流/截图位图/
 *    授权往返/取景帧流重建整套 AIDL 桥并处理 :web 崩溃后的会话重挂，属 v0.2 级
 *    重构，在发布里程碑引入风险不成比例；
 * 2. 规格诉求的隔离收益已部分达成：WebView 渲染本就跑在 Chromium 沙箱渲染进程，
 *    宿主已实现渲染进程崩溃自恢复（onRenderProcessGone ≤3 次）+ 前台优先级策略。
 * 本壳保留为 v0.2 切换的就绪底座（独立进程 Activity + 降级提示已可用）。
 */
class RunnerWebHost : Activity() {

    private lateinit var host: WebViewHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val projectDir = intent.getStringExtra(EXTRA_PROJECT_DIR)
        if (projectDir.isNullOrBlank()) {
            finish() // 缺参直接退出；host 未初始化，onResume/onPause/onDestroy 需守卫
            return
        }
        host = WebViewHost(this, File(projectDir))
        setContentView(host.webView)
        host.onWebViewRecreated = { setContentView(host.webView) }
        host.onCrashGiveUp = { setContentView(crashGiveUpView()) }
    }

    override fun onResume() {
        super.onResume()
        if (::host.isInitialized) host.resume()
    }

    override fun onPause() {
        if (::host.isInitialized) host.pause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::host.isInitialized) host.destroy()
        super.onDestroy()
    }

    private fun crashGiveUpView(): android.view.View =
        FrameLayout(this).apply {
            setBackgroundColor(0xFF12131A.toInt())
            addView(
                TextView(this@RunnerWebHost).apply {
                    text = "页面运行时多次崩溃，已停止自动恢复。\n可在调试抽屉查看崩溃报告。"
                    setTextColor(0xFFF2F3F7.toInt())
                    gravity = Gravity.CENTER
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }

    companion object {
        const val EXTRA_PROJECT_DIR = "com.zhique.core.web.EXTRA_PROJECT_DIR"

        fun intent(context: Context, projectDir: File): Intent =
            Intent(context, RunnerWebHost::class.java)
                .putExtra(EXTRA_PROJECT_DIR, projectDir.absolutePath)
    }
}
