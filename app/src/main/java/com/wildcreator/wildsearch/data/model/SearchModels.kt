package com.wildcreator.wildsearch.data.model

/** UI 层直接消费的搜索结果。 */
data class SearchResult(
    val id: String,
    val title: String,
    val url: String,
    val displayUrl: String?,
    val snippet: String?,
    val summary: String?,
    val siteName: String?,
    val siteIcon: String?,
    val publishedAt: String?,
)

/** 时间范围筛选，wire 值与博查 API 的 freshness 参数一致。 */
enum class Freshness(val wire: String, val label: String) {
    NO_LIMIT("noLimit", "不限时间"),
    ONE_DAY("oneDay", "一天内"),
    ONE_WEEK("oneWeek", "一周内"),
    ONE_MONTH("oneMonth", "一个月内"),
    ONE_YEAR("oneYear", "一年内");

    companion object {
        fun fromWire(value: String?): Freshness = entries.firstOrNull { it.wire == value } ?: NO_LIMIT
    }
}

/** 本地持久化的设置项。 */
data class SearchSettings(
    val apiKey: String = "",
    val freshness: Freshness = Freshness.NO_LIMIT,
    val count: Int = 10,
    val summary: Boolean = true,
    val history: List<String> = emptyList(),
    val autoUpdateCheck: Boolean = true,
    val lastUpdateCheckAt: Long = 0L,
)
