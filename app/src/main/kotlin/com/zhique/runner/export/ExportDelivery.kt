package com.zhique.runner.export

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * 导出交付（M6 Task 6.3）：安装（PackageInstaller 会话流，系统确认弹窗，
 * 「未知来源」授权一次）/ 存下载目录（MediaStore）/ 分享（FileProvider）/
 * .jks 备份到电脑（分享）/ 桌面快捷方式（requestPinShortcut，直达导出应用）。
 */
object ExportDelivery {

    /** 安装确认/结果的回执 action（Manifest 注册 [InstallResultReceiver]）。 */
    const val ACTION_INSTALL_COMMIT = "com.zhique.runner.action.INSTALL_COMMIT"

    // ---- 安装 ----

    /** PackageInstaller 全新安装/更新流：写会话 → commit（系统确认弹窗）。 */
    fun installApk(context: Context, apk: File) {
        val packageManager = context.packageManager
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("zhique-export", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(ACTION_INSTALL_COMMIT).setPackage(context.packageName)
            val pi = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(pi.intentSender)
        }
    }

    /** 安装结果回执（状态仅 toast 提示；M10 真机验收覆盖安装流）。 */
    class InstallResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            val text = when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> "请在系统弹窗中确认安装"
                PackageInstaller.STATUS_SUCCESS -> "安装完成"
                else -> "安装未完成${msg?.let { "：$it" } ?: ""}"
            }
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // ---- 存 Downloads ----

    /** 存入公共下载目录（API29+ MediaStore），返回内容 URI。 */
    fun saveToDownloads(context: Context, apk: File, displayName: String): android.net.Uri? = runCatching {
        val values = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out -> apk.inputStream().use { it.copyTo(out) } }
        uri
    }.getOrNull()

    // ---- 分享 ----

    /** 分享 APK（FileProvider cache/exports 只读授权）。 */
    fun shareApk(context: Context, apk: File) {
        shareFile(context, apk, "application/vnd.android.package-archive", "分享应用安装包")
    }

    /** 备份 .jks 到电脑（决策29-3：系统分享为三选路径之一）。 */
    fun shareKeystore(context: Context, jks: File) {
        shareFile(context, jks, "application/octet-stream", "备份签名密钥库（.jks）到电脑")
    }

    private fun shareFile(context: Context, file: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.zqfile", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---- 桌面快捷方式 ----

    /** 请求固定桌面快捷方式：图标（应用名首字+主题色）+ 直达导出应用全屏运行。 */
    fun requestPinShortcut(context: Context, appName: String, packageName: String, iconColor: String): Boolean {
        val sm = context.getSystemService(android.content.pm.ShortcutManager::class.java) ?: return false
        if (!sm.isRequestPinShortcutSupported) return false
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(packageName)
        launch.action = Intent.ACTION_MAIN
        val shortcut = android.content.pm.ShortcutInfo.Builder(context, "zhique-export-$packageName")
            .setShortLabel(appName)
            .setIcon(android.graphics.drawable.Icon.createWithBitmap(iconBitmap(context, appName, iconColor)))
            .setIntent(launch)
            .build()
        return runCatching {
            sm.requestPinShortcut(shortcut, null)
        }.getOrDefault(false)
    }

    private fun iconBitmap(context: Context, appName: String, colorHex: String): Bitmap {
        val size = (108 * context.resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val brandColor = runCatching { android.graphics.Color.parseColor(colorHex) }.getOrDefault(0xFF46509F.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = brandColor
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = 0xFFFFFFFF.toInt()
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = size * 0.45f
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(appName.take(1).ifBlank { "应" }, size / 2f, y, paint)
        return bitmap
    }
}
