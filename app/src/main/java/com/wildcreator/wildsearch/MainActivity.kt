package com.wildcreator.wildsearch

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wildcreator.wildsearch.ui.RawJsonDialog
import com.wildcreator.wildsearch.ui.SearchScreen
import com.wildcreator.wildsearch.ui.SearchUiState
import com.wildcreator.wildsearch.ui.SearchViewModel
import com.wildcreator.wildsearch.ui.SettingsScreen
import com.wildcreator.wildsearch.ui.UpdateUiState
import com.wildcreator.wildsearch.ui.UpdateViewModel
import com.wildcreator.wildsearch.ui.components.UpdateDialog
import com.wildcreator.wildsearch.ui.theme.WildSearchTheme
import com.wildcreator.wildsearch.util.canInstallPackages
import com.wildcreator.wildsearch.util.installApk
import com.wildcreator.wildsearch.util.openInBrowser

class MainActivity : ComponentActivity() {

    private val searchViewModel: SearchViewModel by viewModels { SearchViewModel.factory(this) }

    private val updateViewModel: UpdateViewModel by viewModels { UpdateViewModel.factory(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WildSearchTheme {
                val searchState by searchViewModel.state.collectAsStateWithLifecycle()
                val updateState by updateViewModel.state.collectAsStateWithLifecycle()
                AppRoot(
                    searchState = searchState,
                    searchViewModel = searchViewModel,
                    updateState = updateState,
                    updateViewModel = updateViewModel,
                )
            }
        }
    }
}

@Composable
private fun AppRoot(
    searchState: SearchUiState,
    searchViewModel: SearchViewModel,
    updateState: UpdateUiState,
    updateViewModel: UpdateViewModel,
) {
    val context = LocalContext.current
    // 进程常驻时也能自动更新：每次回到前台重新评估 6 小时节流。
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        updateViewModel.onForeground()
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updateViewModel.onInstallPermissionResult(context.canInstallPackages())
    }

    // 下载完成 → 校验安装权限 → 调起系统安装器
    val phase = updateState.phase
    LaunchedEffect(phase) {
        if (phase !is UpdateUiState.Phase.InstallReady) return@LaunchedEffect
        if (!context.canInstallPackages()) {
            updateViewModel.awaitInstallPermission()
            try {
                permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            } catch (_: android.content.ActivityNotFoundException) {
                updateViewModel.installFailed("无法打开安装权限设置页，请到系统设置授权后重试")
            } catch (_: SecurityException) {
                updateViewModel.installFailed("系统拒绝打开安装权限设置页，请手动授权后重试")
            }
            return@LaunchedEffect
        }
        val apk = updateViewModel.beginInstall() ?: return@LaunchedEffect
        if (context.installApk(apk.file)) {
            updateViewModel.consumeInstall()
        } else {
            updateViewModel.installFailed("调起安装器失败，APK 已保存在缓存目录")
        }
    }

    LaunchedEffect(searchState.message) {
        val message = searchState.message ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        searchViewModel.consumeMessage()
    }

    LaunchedEffect(updateState.message) {
        val message = updateState.message ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        updateViewModel.consumeMessage()
    }

    if (!searchState.ready) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (searchState.showSettings) {
        BackHandler { searchViewModel.closeSettings() }
        SettingsScreen(
            apiKey = searchState.apiKey,
            savingApiKey = searchState.savingApiKey,
            apiKeySaveError = searchState.apiKeySaveError,
            versionName = BuildConfig.VERSION_NAME,
            historyCount = searchState.history.size,
            updateState = updateState,
            onSaveKey = searchViewModel::saveApiKey,
            onClearHistory = searchViewModel::clearHistory,
            onCheckUpdate = {
                if (updateState.phase is UpdateUiState.Phase.PermissionRequired || updateState.phase is UpdateUiState.Phase.InstallFailed)
                    updateViewModel.retry() else updateViewModel.checkManually()
            },
            onToggleAutoCheck = updateViewModel::setAutoCheck,
            onBack = searchViewModel::closeSettings,
        )
    } else {
        SearchScreen(
            state = searchState,
            onQueryChange = searchViewModel::onQueryChange,
            onSubmit = { searchViewModel.submit(searchState.query) },
            onRetry = searchViewModel::retry,
            onHistoryClick = searchViewModel::openHistory,
            onRemoveHistory = searchViewModel::removeHistory,
            onClearHistory = searchViewModel::clearHistory,
            onFreshnessChange = searchViewModel::setFreshness,
            onCountChange = searchViewModel::setCount,
            onSummaryChange = searchViewModel::setSummary,
            onOpenSettings = searchViewModel::openSettings,
            onShowRawJson = searchViewModel::showRawJson,
        )
    }

    if (updateState.dialogVisible) {
        UpdateDialog(
            phase = updateState.phase,
            onDismiss = updateViewModel::dismissDialog,
            onDownload = updateViewModel::startDownload,
            onRetry = updateViewModel::retry,
            onOpenReleasePage = { context.openInBrowser(it) },
        )
    }

    val raw = searchState.rawJson
    if (searchState.showRawJson && raw != null) {
        RawJsonDialog(text = raw, onDismiss = searchViewModel::hideRawJson)
    }
}
