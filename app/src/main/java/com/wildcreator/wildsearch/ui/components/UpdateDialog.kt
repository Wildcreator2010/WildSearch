package com.wildcreator.wildsearch.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wildcreator.wildsearch.BuildConfig
import com.wildcreator.wildsearch.ui.UpdateUiState
import com.wildcreator.wildsearch.util.formatBytes

/**
 * 更新流程的单一对话框：检查中 / 发现新版本 / 下载中 / 失败 / 未配置。
 * 状态全部来自 [UpdateUiState.Phase]，不做本地副本，避免和 ViewModel 不一致。
 */
@Composable
fun UpdateDialog(
    phase: UpdateUiState.Phase,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onOpenReleasePage: (String) -> Unit,
) {
    when (phase) {
        UpdateUiState.Phase.Idle,
        is UpdateUiState.Phase.InstallReady,
        is UpdateUiState.Phase.Installing,
        UpdateUiState.Phase.UpToDate,
        -> Unit

        UpdateUiState.Phase.Checking -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("检查更新") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text("正在向 GitHub 查询最新版本…")
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("取消") }
            },
        )

        is UpdateUiState.Phase.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("发现新版本 ${phase.info.version}") },
            text = {
                Column {
                    Text(
                        text = buildString {
                            append("当前版本 ")
                            append(BuildConfig.VERSION_NAME)
                            phase.info.sizeBytes?.let { append("　·　安装包 ").append(formatBytes(it)) }
                            phase.info.publishedAt?.let { append("　·　").append(it) }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val notes = phase.info.releaseNotes
                    if (!notes.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        SelectionContainer {
                            Text(
                                text = notes,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .heightIn(max = 260.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "下载后由系统安装器完成安装，需要授予「安装未知应用」权限。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onDownload) { Text("立即下载") }
            },
            dismissButton = {
                Row {
                    val page = phase.info.releasePageUrl
                    if (!page.isNullOrBlank()) {
                        TextButton(onClick = { onOpenReleasePage(page) }) { Text("更新页") }
                    }
                    TextButton(onClick = onDismiss) { Text("稍后") }
                }
            },
        )

        is UpdateUiState.Phase.Downloading -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("正在下载 ${phase.info.version}") },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { phase.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = buildString {
                            append((phase.progress * 100).toInt()).append("%")
                            phase.info.sizeBytes?.let { append("　·　共 ").append(formatBytes(it)) }
                            append("　·　").append(phase.info.assetName)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("后台下载") } },
        )

        is UpdateUiState.Phase.PermissionRequired,
        is UpdateUiState.Phase.InstallFailed,
        -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("继续安装") },
            text = { Text(when (phase) {
                is UpdateUiState.Phase.PermissionRequired -> phase.message
                is UpdateUiState.Phase.InstallFailed -> phase.message
                else -> ""
            }) },
            confirmButton = { TextButton(onClick = onRetry) { Text("继续安装") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } },
        )

        is UpdateUiState.Phase.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("更新失败") },
            text = { Text(phase.message) },
            confirmButton = {
                TextButton(onClick = onRetry) { Text("重试") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("关闭") }
            },
        )

        UpdateUiState.Phase.NotConfigured -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("未配置更新仓库") },
            text = {
                SelectionContainer {
                    Text(
                        text = "在 gradle.properties 里设置\n" +
                            "wildsearch.update.repo=你的用户名/仓库名\n" +
                            "然后重新构建。Release 需附带 .apk 附件，tag 使用 semver（如 v1.1.0）。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("知道了") }
            },
        )
    }
}

/** 供设置页复用的状态文案。 */
fun describeUpdatePhase(phase: UpdateUiState.Phase, autoCheckEnabled: Boolean): String = when (phase) {
    UpdateUiState.Phase.Idle -> if (autoCheckEnabled) "启动时自动检查（每 6 小时最多一次）" else "仅手动检查"
    UpdateUiState.Phase.Checking -> "正在检查…"
    is UpdateUiState.Phase.Available -> "发现新版本 ${phase.info.version}"
    is UpdateUiState.Phase.Downloading -> "正在下载 ${(phase.progress * 100).toInt()}%"
    is UpdateUiState.Phase.InstallReady -> "已下载，正在调起安装器"
    is UpdateUiState.Phase.Installing -> "已调起系统安装器"
    is UpdateUiState.Phase.PermissionRequired -> "已下载，等待安装授权"
    is UpdateUiState.Phase.InstallFailed -> "安装失败：${phase.message}"
    is UpdateUiState.Phase.Failed -> "检查失败：${phase.message}"
    UpdateUiState.Phase.NotConfigured -> "未配置更新仓库"
    UpdateUiState.Phase.UpToDate -> "已是最新版本"
}

/** 便于设置页判断按钮可用性。 */
fun isUpdateBusy(phase: UpdateUiState.Phase): Boolean =
    phase is UpdateUiState.Phase.Checking || phase is UpdateUiState.Phase.Downloading ||
        phase is UpdateUiState.Phase.InstallReady || phase is UpdateUiState.Phase.Installing
