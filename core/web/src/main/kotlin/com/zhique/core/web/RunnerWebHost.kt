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
 */
class RunnerWebHost : Activity() {

    private lateinit var host: WebViewHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val projectDir = intent.getStringExtra(EXTRA_PROJECT_DIR)
        if (projectDir.isNullOrBlank()) {
            finish()
            return
        }
        host = WebViewHost(this, File(projectDir))
        setContentView(host.webView)
        host.onWebViewRecreated = { setContentView(host.webView) }
        host.onCrashGiveUp = { setContentView(crashGiveUpView()) }
    }

    override fun onResume() {
        super.onResume()
        host.resume()
    }

    override fun onPause() {
        host.pause()
        super.onPause()
    }

    override fun onDestroy() {
        host.destroy()
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
