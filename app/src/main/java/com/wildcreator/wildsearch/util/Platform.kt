package com.wildcreator.wildsearch.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast

/** 用外部浏览器打开链接；没有可用浏览器时给出提示而不是崩溃。 */
fun Context.openInBrowser(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "没有可以打开该链接的应用", Toast.LENGTH_SHORT).show()
    }
}

/**
 * 复制文本。Android 13 起系统自带复制提示，故不再重复弹 Toast。
 */
fun Context.copyToClipboard(text: String, label: String = "已复制") {
    val manager = getSystemService(ClipboardManager::class.java)
    manager?.setPrimaryClip(ClipData.newPlainText("WildSearch", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
    }
}

/** 系统分享面板。 */
fun Context.shareText(title: String, url: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, "$title\n$url")
    }
    try {
        startActivity(Intent.createChooser(send, "分享"))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "没有可以分享的应用", Toast.LENGTH_SHORT).show()
    }
}

/** 取主机名，用于站点图标缺失时兜底显示。 */
fun hostOf(url: String): String? = runCatching { Uri.parse(url).host }.getOrNull()

/** 大数字友好展示：606721 -> 60.7 万，10000000 -> 1000 万，123456789 -> 1.2 亿。 */
fun formatCount(value: Long): String = when {
    value >= 100_000_000L -> "%.1f 亿".format(value / 100_000_000.0)
    value >= 1_000_000L -> "%.0f 万".format(value / 10_000.0)
    value >= 10_000L -> "%.1f 万".format(value / 10_000.0)
    else -> value.toString()
}
