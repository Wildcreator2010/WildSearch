package com.wildcreator.wildsearch.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wildcreator.wildsearch.data.model.BochaJson
import com.wildcreator.wildsearch.data.model.Freshness
import com.wildcreator.wildsearch.data.model.SearchSettings
import com.wildcreator.wildsearch.data.remote.isValidApiKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.IOException

private val Context.wildSearchDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "wildsearch_settings",
)

/**
 * 设置与搜索历史的唯一持久化入口。
 * API Key 只存本机 App 私有目录，不写入日志、不上传、不进构建产物。
 */
class SettingsRepository(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.wildSearchDataStore)

    val settings: Flow<SearchSettings> = store.data
        .catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map(::toSettings)

    suspend fun updateApiKey(key: String) {
        require(isValidApiKey(key)) { "API Key 格式不合法" }
        store.edit { it[KEY_API_KEY] = key.trim() }
    }

    suspend fun updateFreshness(freshness: Freshness) {
        store.edit { it[KEY_FRESHNESS] = freshness.wire }
    }

    suspend fun updateCount(count: Int) {
        store.edit { it[KEY_COUNT] = count.coerceIn(MIN_COUNT, MAX_COUNT) }
    }

    suspend fun updateSummary(enabled: Boolean) {
        store.edit { it[KEY_SUMMARY] = enabled }
    }

    suspend fun updateAutoUpdateCheck(enabled: Boolean) {
        store.edit { it[KEY_AUTO_UPDATE_CHECK] = enabled }
    }

    /** 记录本次检查时间，用于启动自动检查的节流。 */
    suspend fun markUpdateChecked(timestamp: Long) {
        store.edit { it[KEY_LAST_UPDATE_CHECK] = timestamp }
    }

    /** 新词置顶、去重（忽略大小写）、最多保留 [HISTORY_LIMIT] 条。 */
    suspend fun pushHistory(query: String) {
        val value = query.trim()
        if (value.isEmpty()) return
        store.edit { prefs ->
            val next = (listOf(value) + decodeHistory(prefs[KEY_HISTORY]).filterNot { it.equals(value, true) })
                .take(HISTORY_LIMIT)
            prefs[KEY_HISTORY] = encodeHistory(next)
        }
    }

    suspend fun removeHistory(query: String) {
        store.edit { prefs ->
            prefs[KEY_HISTORY] = encodeHistory(decodeHistory(prefs[KEY_HISTORY]).filterNot { it == query })
        }
    }

    suspend fun clearHistory() {
        store.edit { it.remove(KEY_HISTORY) }
    }

    private fun toSettings(prefs: Preferences): SearchSettings = SearchSettings(
        apiKey = prefs[KEY_API_KEY].orEmpty(),
        freshness = Freshness.fromWire(prefs[KEY_FRESHNESS]),
        count = (prefs[KEY_COUNT] ?: DEFAULT_COUNT).coerceIn(MIN_COUNT, MAX_COUNT),
        summary = prefs[KEY_SUMMARY] ?: true,
        history = decodeHistory(prefs[KEY_HISTORY]),
        autoUpdateCheck = prefs[KEY_AUTO_UPDATE_CHECK] ?: true,
        lastUpdateCheckAt = prefs[KEY_LAST_UPDATE_CHECK] ?: 0L,
    )

    private fun decodeHistory(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            BochaJson.decodeFromString(ListSerializer(String.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun encodeHistory(values: List<String>): String =
        BochaJson.encodeToString(ListSerializer(String.serializer()), values)

    companion object {
        const val MIN_COUNT: Int = 1
        const val MAX_COUNT: Int = 50
        const val DEFAULT_COUNT: Int = 10
        const val HISTORY_LIMIT: Int = 20

        private val KEY_API_KEY = stringPreferencesKey("api_key")
        private val KEY_FRESHNESS = stringPreferencesKey("freshness")
        private val KEY_COUNT = intPreferencesKey("count")
        private val KEY_SUMMARY = booleanPreferencesKey("summary")
        private val KEY_HISTORY = stringPreferencesKey("history")
        private val KEY_AUTO_UPDATE_CHECK = booleanPreferencesKey("auto_update_check")
        private val KEY_LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
    }
}
