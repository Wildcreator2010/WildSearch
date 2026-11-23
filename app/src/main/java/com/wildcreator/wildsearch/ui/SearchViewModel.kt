package com.wildcreator.wildsearch.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wildcreator.wildsearch.data.SettingsRepository
import com.wildcreator.wildsearch.data.model.Freshness
import com.wildcreator.wildsearch.data.model.SearchRequest
import com.wildcreator.wildsearch.data.model.SearchResult
import com.wildcreator.wildsearch.data.remote.BochaApi
import com.wildcreator.wildsearch.data.remote.SearchError
import com.wildcreator.wildsearch.data.remote.SearchOutcome
import com.wildcreator.wildsearch.data.remote.isValidApiKey
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val ready: Boolean = false,
    val query: String = "",
    val activeQuery: String? = null,
    val loading: Boolean = false,
    val results: List<SearchResult> = emptyList(),
    val totalMatches: Long? = null,
    val error: SearchError? = null,
    val rawJson: String? = null,
    val showRawJson: Boolean = false,
    val showSettings: Boolean = false,
    val apiKey: String = "",
    val savingApiKey: Boolean = false,
    val apiKeySaveError: String? = null,
    val message: String? = null,
    val freshness: Freshness = Freshness.NO_LIMIT,
    val count: Int = SettingsRepository.DEFAULT_COUNT,
    val summary: Boolean = true,
    val history: List<String> = emptyList(),
)

class SearchViewModel(
    private val repository: SettingsRepository,
    private val api: BochaApi = BochaApi(),
) : ViewModel() {
    constructor(context: Context) : this(SettingsRepository(context))

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var generation = 0L

    init {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                _state.update {
                    it.copy(
                        ready = true,
                        apiKey = settings.apiKey,
                        freshness = settings.freshness,
                        count = settings.count,
                        summary = settings.summary,
                        history = settings.history,
                    )
                }
            }
        }
    }

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
    }

    fun submit(rawQuery: String) {
        val operation = ++generation
        searchJob?.cancel()
        val query = rawQuery.trim()
        if (query.isEmpty()) {
            _state.update { it.copy(error = SearchError.EmptyQuery, loading = false) }
            return
        }
        if (query.length > MAX_QUERY_LENGTH) {
            _state.update { it.copy(error = SearchError.QueryTooLong, loading = false) }
            return
        }

        val snapshot = _state.value
        if (snapshot.apiKey.isBlank()) {
            _state.update { it.copy(error = SearchError.NoApiKey, showSettings = true, loading = false) }
            return
        }
        if (!isValidApiKey(snapshot.apiKey)) {
            _state.update { it.copy(error = SearchError.InvalidApiKey, showSettings = true, loading = false) }
            return
        }

        searchJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = true,
                    error = null,
                    activeQuery = query,
                    results = emptyList(),
                    totalMatches = null,
                    rawJson = null,
                )
            }
            try { repository.pushHistory(query) } catch (_: IOException) {
                _state.update { it.copy(message = "搜索历史保存失败") }
            }

            val request = SearchRequest(
                query = query,
                freshness = snapshot.freshness.wire,
                summary = snapshot.summary,
                count = snapshot.count,
            )

            val outcome = api.search(snapshot.apiKey, request)
            if (operation != generation) return@launch
            when (outcome) {
                is SearchOutcome.Success -> _state.update {
                    it.copy(
                        loading = false,
                        results = outcome.results,
                        totalMatches = outcome.totalMatches,
                        rawJson = outcome.rawJson,
                        error = null,
                    )
                }

                is SearchOutcome.Failure -> _state.update {
                    it.copy(
                        loading = false,
                        error = outcome.error,
                        rawJson = outcome.rawJson,
                    )
                }
            }
        }
    }

    fun retry() {
        val snapshot = _state.value
        val inputError = snapshot.error == SearchError.EmptyQuery || snapshot.error == SearchError.QueryTooLong
        submit(if (inputError) snapshot.query else snapshot.activeQuery ?: snapshot.query)
    }

    fun openHistory(query: String) {
        _state.update { it.copy(query = query) }
        submit(query)
    }

    fun setFreshness(freshness: Freshness) {
        _state.update { it.copy(freshness = freshness) }
        viewModelScope.launch { repository.updateFreshness(freshness) }
    }

    fun setCount(count: Int) {
        val safe = count.coerceIn(SettingsRepository.MIN_COUNT, SettingsRepository.MAX_COUNT)
        _state.update { it.copy(count = safe) }
        viewModelScope.launch { repository.updateCount(safe) }
    }

    fun setSummary(enabled: Boolean) {
        _state.update { it.copy(summary = enabled) }
        viewModelScope.launch { repository.updateSummary(enabled) }
    }

    fun saveApiKey(key: String) {
        if (_state.value.savingApiKey) return
        if (!isValidApiKey(key)) {
            _state.update { it.copy(apiKeySaveError = SearchError.InvalidApiKey.userMessage, message = null) }
            return
        }
        _state.update { it.copy(savingApiKey = true, apiKeySaveError = null, message = null) }
        viewModelScope.launch {
            try {
                repository.updateApiKey(key.trim())
                _state.update { it.copy(apiKey = key.trim(), savingApiKey = false, error = null, message = "API Key 已保存") }
            } catch (_: IOException) {
                _state.update { it.copy(savingApiKey = false, apiKeySaveError = "保存失败，请检查可用存储空间后重试") }
            }
        }
    }

    fun consumeMessage() { _state.update { it.copy(message = null) } }

    fun clearHistory() {
        viewModelScope.launch { repository.clearHistory() }
    }

    fun removeHistory(query: String) {
        viewModelScope.launch { repository.removeHistory(query) }
    }

    fun openSettings() {
        _state.update { it.copy(showSettings = true) }
    }

    fun closeSettings() {
        _state.update { it.copy(showSettings = false) }
    }

    fun showRawJson() {
        _state.update { it.copy(showRawJson = true) }
    }

    fun hideRawJson() {
        _state.update { it.copy(showRawJson = false) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    companion object {
        /** 仅用于拦住明显异常的粘贴内容，不是博查的接口限制。 */
        const val MAX_QUERY_LENGTH: Int = 5_000

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer { SearchViewModel(appContext) }
            }
        }
    }
}
