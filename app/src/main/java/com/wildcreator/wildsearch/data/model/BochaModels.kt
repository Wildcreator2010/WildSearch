package com.wildcreator.wildsearch.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * 请求体。字段含义见博查 Web Search API：
 * query 必填；freshness 取 noLimit / oneDay / oneWeek / oneMonth / oneYear；
 * summary 控制是否返回长摘要；count 为期望返回条数。
 */
@Serializable
data class SearchRequest(
    val query: String,
    val freshness: String? = null,
    val summary: Boolean? = null,
    val count: Int? = null,
)

/**
 * 响应外层信封。
 *
 * 实测（对 api.bochaai.com 发无效 Key 请求观测）服务端存在多种互不兼容的封装：
 *  - 成功体：{"code":200,"log_id":..,"msg":null,"data":{..}}   code 是**数字**
 *  - 业务错误：{"code":"401","log_id":..,"message":"Invalid API KEY"}  code 是**字符串**，文案字段是 message
 *  - 网关/框架错误(415/404)：Spring 默认体 {"timestamp","status","error","message","path","trace"}
 *
 * 因此 code 用 JsonElement 收，文案用多候选兜底，任何字段变动都不会让解析崩溃。
 * 注意：HTTP 状态码才是成败依据，body 里的 code 不可信（实测出现过 HTTP 400 + code:"500"）。
 */
@Serializable
data class BochaResponse(
    val code: JsonElement? = null,
    @SerialName("log_id") val logId: String? = null,
    val msg: String? = null,
    val message: String? = null,
    val error: String? = null,
    @SerialName("subCode") val subCode: String? = null,
    val success: Boolean? = null,
    val data: BochaData? = null,
)

@Serializable
data class BochaData(
    @SerialName("_type") val type: String? = null,
    val queryContext: QueryContext? = null,
    val webPages: WebPages? = null,
)

@Serializable
data class QueryContext(
    val originalQuery: String? = null,
)

@Serializable
data class WebPages(
    val webSearchUrl: String? = null,
    val totalEstimatedMatches: Long? = null,
    val value: List<WebPageItem> = emptyList(),
)

/** 单条网页结果。字段与 Bing Web Search 兼容。 */
@Serializable
data class WebPageItem(
    val id: String? = null,
    val name: String? = null,
    val url: String? = null,
    val displayUrl: String? = null,
    val snippet: String? = null,
    val summary: String? = null,
    val siteName: String? = null,
    val siteIcon: String? = null,
    val datePublished: String? = null,
    val dateLastCrawled: String? = null,
)

/**
 * 宽松解析：忽略未知字段、容忍非法值、缺省字段回落到默认值。
 * 这是应对官方文档未公开字段细节的兜底手段。
 */
val BochaJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}
