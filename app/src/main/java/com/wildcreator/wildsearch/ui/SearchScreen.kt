package com.wildcreator.wildsearch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wildcreator.wildsearch.data.model.Freshness
import com.wildcreator.wildsearch.data.remote.SearchError
import com.wildcreator.wildsearch.ui.components.ResultCard
import com.wildcreator.wildsearch.util.copyToClipboard
import com.wildcreator.wildsearch.util.formatCount
import com.wildcreator.wildsearch.util.openInBrowser
import com.wildcreator.wildsearch.util.shareText

private val COUNT_OPTIONS = listOf(5, 10, 20, 30, 50)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onRetry: () -> Unit,
    onHistoryClick: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    onFreshnessChange: (Freshness) -> Unit,
    onCountChange: (Int) -> Unit,
    onSummaryChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onShowRawJson: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("野搜", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "博查 Web Search · 全网检索",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            SearchInputRow(
                query = state.query,
                enabled = !state.loading,
                onQueryChange = onQueryChange,
                onSubmit = {
                    keyboard?.hide()
                    onSubmit()
                },
            )
            FilterRow(
                freshness = state.freshness,
                count = state.count,
                summary = state.summary,
                onFreshnessChange = onFreshnessChange,
                onCountChange = onCountChange,
                onSummaryChange = onSummaryChange,
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                SearchContent(
                    state = state,
                    onRetry = onRetry,
                    onHistoryClick = onHistoryClick,
                    onRemoveHistory = onRemoveHistory,
                    onClearHistory = onClearHistory,
                    onOpenSettings = onOpenSettings,
                    onShowRawJson = onShowRawJson,
                )
            }
        }
    }
}

@Composable
private fun SearchInputRow(
    query: String,
    enabled: Boolean,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            placeholder = { Text("搜索全网，例如：什么是 RAG") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "清空输入")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = onSubmit, enabled = enabled, shape = RoundedCornerShape(24.dp)) {
            Text("搜索")
        }
    }
}

@Composable
private fun FilterRow(
    freshness: Freshness,
    count: Int,
    summary: Boolean,
    onFreshnessChange: (Freshness) -> Unit,
    onCountChange: (Int) -> Unit,
    onSummaryChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Freshness.entries.forEach { option ->
                FilterChip(
                    selected = freshness == option,
                    onClick = { onFreshnessChange(option) },
                    label = { Text(option.label) },
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CountSelector(count = count, onSelect = onCountChange)
            Spacer(Modifier.weight(1f))
            Text("长摘要", style = MaterialTheme.typography.labelMedium)
            Switch(checked = summary, onCheckedChange = onSummaryChange)
        }
    }
}

@Composable
private fun CountSelector(count: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text("每页 $count 条") },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            COUNT_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text("$option 条") },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchContent(
    state: SearchUiState,
    onRetry: () -> Unit,
    onHistoryClick: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onShowRawJson: () -> Unit,
) {
    val context = LocalContext.current
    val error = state.error

    when {
        state.loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("正在搜索…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        error != null -> {
            ErrorState(
                error = error,
                hasRaw = state.rawJson != null,
                onRetry = onRetry,
                onOpenSettings = onOpenSettings,
                onShowRawJson = onShowRawJson,
            )
        }

        state.activeQuery == null -> {
            HistoryState(
                history = state.history,
                hasApiKey = state.apiKey.isNotBlank(),
                onHistoryClick = onHistoryClick,
                onRemoveHistory = onRemoveHistory,
                onClearHistory = onClearHistory,
                onOpenSettings = onOpenSettings,
            )
        }

        state.results.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text("没有找到相关网页", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "试试更换关键词，或把时间范围调宽。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onShowRawJson) { Text("查看原始响应") }
                }
            }
        }

        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val total = state.totalMatches
                        Text(
                            text = buildString {
                                append("「").append(state.activeQuery.orEmpty()).append("」")
                                if (total != null && total > 0) {
                                    append(" · 约 ").append(formatCount(total)).append(" 条匹配")
                                }
                                append(" · 返回 ").append(state.results.size).append(" 条")
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onShowRawJson) { Text("原始响应") }
                    }
                }
                itemsIndexed(
                    items = state.results,
                    key = { index, item -> "$index-${item.url}" },
                ) { _, item ->
                    ResultCard(
                        result = item,
                        onOpen = { context.openInBrowser(item.url) },
                        onCopyLink = { context.copyToClipboard(item.url, "链接已复制") },
                        onShare = { context.shareText(item.title, item.url) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorState(
    error: SearchError,
    hasRaw: Boolean,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onShowRawJson: () -> Unit,
) {
    val needsKey = error is SearchError.NoApiKey || error is SearchError.Unauthorized
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = error.userMessage,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (needsKey) {
                Button(onClick = onOpenSettings) { Text("去设置 API Key") }
            }
            OutlinedButton(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("重试")
            }
        }
        if (hasRaw) {
            TextButton(onClick = onShowRawJson) { Text("查看原始响应") }
        }
    }
}

@Composable
private fun HistoryState(
    history: List<String>,
    hasApiKey: Boolean,
    onHistoryClick: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (history.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("输入关键词，搜索整个中文互联网", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "结果由博查 Web Search API 提供，包含标题、摘要、长摘要与来源时间。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (!hasApiKey) {
                Spacer(Modifier.height(18.dp))
                Button(onClick = onOpenSettings) { Text("先配置 API Key") }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "最近搜索",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClearHistory) { Text("清空") }
            }
        }
        items(items = history, key = { "history-$it" }) { keyword ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onHistoryClick(keyword) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = keyword,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onRemoveHistory(keyword) }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "删除这条记录",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 原始响应查看器：接口字段与文档存在偏差时，用它自证实际返回。 */
@Composable
fun RawJsonDialog(text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val preview = remember(text) { com.wildcreator.wildsearch.data.remote.rawResponsePreview(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("原始响应") },
        text = {
            SelectionContainer {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { context.copyToClipboard(preview, "原始响应已复制") }) { Text("复制") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
