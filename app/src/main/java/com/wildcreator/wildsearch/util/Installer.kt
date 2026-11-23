package com.wildcreator.wildsearch.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Android 8.0+ 需要用户为「未知来源安装」单独授权。 */
fun Context.canInstallPackages(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

/** 跳到本应用的「安装未知应用」授权页。 */
fun Context.openUnknownSourcesSettings() {
    try {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "无法打开安装权限设置页", Toast.LENGTH_SHORT).show()
    }
}

/**
 * 通过 FileProvider 调起系统安装器。
 * 直接暴露 file:// 路径在 Android 7+ 会触发 FileUriExposedException，必须走 content URI。
 */
fun Context.installApk(file: File): Boolean {
    return try {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        true
    } catch (error: Exception) {
        Toast.makeText(this, "调起安装器失败：${error.message}", Toast.LENGTH_LONG).show()
        false
    }
}
