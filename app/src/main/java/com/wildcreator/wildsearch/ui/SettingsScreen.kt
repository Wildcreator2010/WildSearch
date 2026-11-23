package com.wildcreator.wildsearch.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.wildcreator.wildsearch.data.remote.BochaApi
import com.wildcreator.wildsearch.data.SettingsRepository
import com.wildcreator.wildsearch.ui.components.describeUpdatePhase
import com.wildcreator.wildsearch.ui.components.isUpdateBusy
import com.wildcreator.wildsearch.util.openInBrowser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    apiKey: String,
    savingApiKey: Boolean,
    apiKeySaveError: String?,
    versionName: String,
    historyCount: Int,
    updateState: UpdateUiState,
    onSaveKey: (String) -> Unit,
    onClearHistory: () -> Unit,
    onCheckUpdate: () -> Unit,
    onToggleAutoCheck: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var draft by remember(apiKey) { mutableStateOf(apiKey) }
    var visible by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("博查 API Key", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = apiKeySaveError != null,
                        supportingText = { apiKeySaveError?.let { Text(it) } },
                        label = { Text("API Key") },
                        placeholder = { Text("粘贴 open.bochaai.com 生成的 Key") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (visible) KeyboardType.Text else KeyboardType.Password,
                        ),
                        visualTransformation = if (visible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            TextButton(onClick = { visible = !visible }) {
                                Text(if (visible) "隐藏" else "显示")
                            }
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !savingApiKey,
                            onClick = { onSaveKey(draft) },
                        ) {
                            Text(if (savingApiKey) "保存中…" else "保存")
                        }
                        TextButton(onClick = { context.openInBrowser("https://open.bochaai.com") }) {
                            Text("获取 API Key")
                        }
                    }
                    Text(
                        text = "密钥只保存在本机应用私有目录（DataStore），不写日志、不上传、不进构建产物。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("接口信息", style = MaterialTheme.typography.titleSmall)
                    InfoRow("接口地址", BochaApi.ENDPOINT)
                    InfoRow("请求方式", "POST · JSON")
                    InfoRow("鉴权", "Authorization: Bearer <API Key>")
                    InfoRow(
                        "可选参数",
                        "freshness / summary / count（查询条数 ${SettingsRepository.MIN_COUNT}–${SettingsRepository.MAX_COUNT}）",
                    )
                }
            }

            OutlinedCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("本地数据", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "搜索历史 $historyCount 条（最多 ${SettingsRepository.HISTORY_LIMIT} 条）",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                onClearHistory()
                                Toast.makeText(context, "搜索历史已清空", Toast.LENGTH_SHORT).show()
                            },
                        ) {
                            Text("清空")
                        }
                    }
                }
            }

            OutlinedCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("版本更新", style = MaterialTheme.typography.titleSmall)
                    InfoRow("当前版本", versionName)
                    Text(
                        text = describeUpdatePhase(updateState.phase, updateState.autoCheckEnabled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "启动时自动检查更新",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = updateState.autoCheckEnabled,
                            onCheckedChange = onToggleAutoCheck,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = onCheckUpdate,
                            enabled = !isUpdateBusy(updateState.phase),
                        ) {
                            Text(if (updateState.phase is UpdateUiState.Phase.PermissionRequired || updateState.phase is UpdateUiState.Phase.InstallFailed) "继续安装" else "检查更新")
                        }
                        if (!updateState.repoConfigured) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "未配置更新仓库",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            OutlinedCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("关于", style = MaterialTheme.typography.titleSmall)
                    InfoRow("应用版本", versionName)
                    InfoRow("数据来源", "博查 AI 开放平台 Web Search API")
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Text(
                        text = "本应用自带结果展示能力，搜索结果的准确性与可用性由博查服务决定；" +
                            "请遵守博查开放平台的服务条款与配额限制。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
