package com.wildcreator.wildsearch.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wildcreator.wildsearch.BuildConfig
import com.wildcreator.wildsearch.data.SettingsRepository
import com.wildcreator.wildsearch.data.remote.DownloadedApk
import com.wildcreator.wildsearch.data.remote.UpdateCheckResult
import com.wildcreator.wildsearch.data.remote.UpdateException
import com.wildcreator.wildsearch.data.remote.UpdateInfo
import com.wildcreator.wildsearch.data.remote.UpdateRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

data class UpdateUiState(
    val currentVersion: String = BuildConfig.VERSION_NAME,
    val phase: Phase = Phase.Idle,
    val dialogVisible: Boolean = false,
    val autoCheckEnabled: Boolean = true,
    val repoConfigured: Boolean = BuildConfig.UPDATE_REPO.contains('/'),
    /** 一次性提示，由 UI 消费后清空（Toast）。 */
    val message: String? = null,
) {
    sealed interface Phase {
        data object Idle : Phase
        data object Checking : Phase
        data class Available(val info: UpdateInfo) : Phase
        data class Downloading(val info: UpdateInfo, val progress: Float) : Phase
        data class InstallReady(val info: UpdateInfo, val apk: DownloadedApk) : Phase
        data class Installing(val info: UpdateInfo, val apk: DownloadedApk) : Phase
        data class PermissionRequired(val info: UpdateInfo, val apk: DownloadedApk, val message: String = "请允许安装未知来源应用，然后继续安装") : Phase
        data class InstallFailed(val info: UpdateInfo, val apk: DownloadedApk, val message: String) : Phase
        data class Failed(val message: String, val info: UpdateInfo? = null) : Phase
        data object NotConfigured : Phase
        data object UpToDate : Phase
    }
}

class UpdateViewModel(
    private val settingsRepository: SettingsRepository,
    private val updateRepository: UpdateRepository,
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    autoCheckOnStart: Boolean = true,
) : ViewModel() {
    constructor(context: Context) : this(SettingsRepository(context), UpdateRepository(context))

    private val _state = MutableStateFlow(UpdateUiState(currentVersion = currentVersion, repoConfigured = updateRepository.configured))
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var job: Job? = null
    @Volatile private var generation = 0L

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _state.update { it.copy(autoCheckEnabled = settings.autoUpdateCheck) }
            }
        }
        if (autoCheckOnStart) viewModelScope.launch { autoCheckIfDue() }
    }

    /** 回到前台时静默补查：真正发不发请求由 6 小时节流与当前状态决定。 */
    fun onForeground() {
        viewModelScope.launch { autoCheckIfDue(fromForeground = true) }
    }

    /** 静默检查：需开关打开、仓库已配置、距上次检查超过阈值，且当前没有进行中的操作。 */
    private suspend fun autoCheckIfDue(fromForeground: Boolean = false) {
        val settings = settingsRepository.settings.first()
        if (!settings.autoUpdateCheck) return
        if (!_state.value.repoConfigured) return
        val elapsed = System.currentTimeMillis() - settings.lastUpdateCheckAt
        if (elapsed in 0 until AUTO_CHECK_INTERVAL_MS) return
        check(interactive = false, allowRecheck = fromForeground)
    }

    fun checkManually() = check(interactive = true)

    private fun check(interactive: Boolean, allowRecheck: Boolean = false) {
        val phase = _state.value.phase
        if (phase is UpdateUiState.Phase.Downloading || phase is UpdateUiState.Phase.InstallReady ||
            phase is UpdateUiState.Phase.Installing || phase is UpdateUiState.Phase.PermissionRequired ||
            phase is UpdateUiState.Phase.InstallFailed) return
        // 启动检查只有一次机会；手动操作取消后，排队中的启动检查不得重新发起。
        if (!interactive && (job?.isActive == true || phase != UpdateUiState.Phase.Idle)) return
        if (!interactive && !allowRecheck && generation != 0L) return
        val operation = ++generation
        job?.cancel()
        _state.update { it.copy(phase = UpdateUiState.Phase.Checking, dialogVisible = interactive, message = null) }
        job = viewModelScope.launch {
            val result = updateRepository.checkForUpdate(currentVersion)
            if (operation != generation) return@launch
            try { settingsRepository.markUpdateChecked(System.currentTimeMillis()) } catch (_: IOException) { /* Check result remains usable. */ }
            if (operation != generation) return@launch

            when (result) {
                is UpdateCheckResult.Available -> _state.update {
                    it.copy(phase = UpdateUiState.Phase.Available(result.info), dialogVisible = true)
                }

                UpdateCheckResult.UpToDate -> _state.update {
                    if (interactive) {
                        it.copy(
                            phase = UpdateUiState.Phase.UpToDate,
                            dialogVisible = false,
                            message = "已是最新版本 ${it.currentVersion}",
                        )
                    } else {
                        it.copy(phase = UpdateUiState.Phase.Idle)
                    }
                }

                UpdateCheckResult.NotConfigured -> _state.update {
                    if (interactive) {
                        it.copy(phase = UpdateUiState.Phase.NotConfigured, dialogVisible = true)
                    } else {
                        it.copy(phase = UpdateUiState.Phase.Idle)
                    }
                }

                is UpdateCheckResult.Failed -> _state.update {
                    if (interactive) {
                        it.copy(phase = UpdateUiState.Phase.Failed(result.message), dialogVisible = true)
                    } else {
                        it.copy(phase = UpdateUiState.Phase.Idle)
                    }
                }
            }
        }
    }

    fun startDownload() {
        val available = _state.value.phase as? UpdateUiState.Phase.Available ?: return
        val operation = ++generation
        job?.cancel()
        _state.update { it.copy(phase = UpdateUiState.Phase.Downloading(available.info, 0f), dialogVisible = true, message = null) }
        job = viewModelScope.launch {
            try {
                val apk = updateRepository.download(available.info) { progress ->
                    _state.update { current ->
                        val downloading = current.phase as? UpdateUiState.Phase.Downloading
                        if (downloading == null || operation != generation || downloading.info != available.info) {
                            current
                        } else {
                            current.copy(phase = UpdateUiState.Phase.Downloading(downloading.info, progress))
                        }
                    }
                }
                if (operation != generation) return@launch
                _state.update {
                    it.copy(phase = UpdateUiState.Phase.InstallReady(available.info, apk), dialogVisible = false)
                }
            } catch (failure: UpdateException) {
                if (operation != generation) return@launch
                _state.update {
                    it.copy(
                        phase = UpdateUiState.Phase.Failed(failure.message ?: "下载失败", available.info),
                        dialogVisible = true,
                    )
                }
            }
        }
    }

    fun dismissDialog() {
        if (_state.value.phase == UpdateUiState.Phase.Checking) {
            ++generation
            job?.cancel()
        }
        _state.update { current ->
            val phase = current.phase
            val nextPhase = when (phase) {
                is UpdateUiState.Phase.Downloading -> phase
                is UpdateUiState.Phase.Available -> UpdateUiState.Phase.Idle
                UpdateUiState.Phase.Checking -> UpdateUiState.Phase.Idle
                else -> phase
            }
            current.copy(dialogVisible = false, phase = nextPhase)
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    /** 安装器已调起后清理状态，避免重复触发。 */
    fun consumeInstall() {
        _state.update { if (it.phase is UpdateUiState.Phase.Installing) it.copy(phase = UpdateUiState.Phase.Idle, dialogVisible = false) else it }
    }

    fun installFailed(reason: String) {
        _state.update {
            val pending = when (val phase = it.phase) {
                is UpdateUiState.Phase.InstallReady -> phase.info to phase.apk
                is UpdateUiState.Phase.Installing -> phase.info to phase.apk
                is UpdateUiState.Phase.PermissionRequired -> phase.info to phase.apk
                else -> return@update it
            }
            it.copy(phase = UpdateUiState.Phase.InstallFailed(pending.first, pending.second, reason), dialogVisible = true)
        }
    }

    fun awaitInstallPermission() {
        _state.update {
            val ready = it.phase as? UpdateUiState.Phase.InstallReady ?: return@update it
            it.copy(phase = UpdateUiState.Phase.PermissionRequired(ready.info, ready.apk), dialogVisible = false)
        }
    }

    fun onInstallPermissionResult(granted: Boolean) {
        _state.update {
            val pending = it.phase as? UpdateUiState.Phase.PermissionRequired ?: return@update it
            if (granted) it.copy(phase = UpdateUiState.Phase.InstallReady(pending.info, pending.apk), dialogVisible = false)
            else it.copy(phase = pending.copy(message = "尚未授予安装权限，请授权后继续安装"), dialogVisible = true)
        }
    }

    /** Transition before the external side effect so recomposition cannot launch twice. */
    fun beginInstall(): DownloadedApk? {
        val ready = _state.value.phase as? UpdateUiState.Phase.InstallReady ?: return null
        if (!ready.apk.file.isFile) {
            _state.update { it.copy(phase = UpdateUiState.Phase.Failed("缓存安装包已丢失，请重新下载", ready.info), dialogVisible = true) }
            return null
        }
        _state.update { it.copy(phase = UpdateUiState.Phase.Installing(ready.info, ready.apk)) }
        return ready.apk
    }

    fun retry() {
        val phase = _state.value.phase
        val pending = when (phase) {
            is UpdateUiState.Phase.PermissionRequired -> phase.info to phase.apk
            is UpdateUiState.Phase.InstallFailed -> phase.info to phase.apk
            else -> null
        }
        if (pending != null) {
            _state.update { it.copy(phase = UpdateUiState.Phase.InstallReady(pending.first, pending.second), dialogVisible = false) }
        } else if (phase is UpdateUiState.Phase.Failed && phase.info != null) {
            _state.update { it.copy(phase = UpdateUiState.Phase.Available(phase.info)) }
            startDownload()
        } else checkManually()
    }

    fun setAutoCheck(enabled: Boolean) {
        _state.update { it.copy(autoCheckEnabled = enabled) }
        viewModelScope.launch { settingsRepository.updateAutoUpdateCheck(enabled) }
    }

    companion object {
        /** 启动自动检查的节流间隔：6 小时。GitHub 匿名 API 有速率限制。 */
        const val AUTO_CHECK_INTERVAL_MS: Long = 6 * 60 * 60 * 1000L

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer { UpdateViewModel(appContext) }
            }
        }
    }
}
