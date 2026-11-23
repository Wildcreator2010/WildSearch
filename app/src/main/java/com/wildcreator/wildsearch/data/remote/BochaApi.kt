package com.wildcreator.wildsearch.data.remote

import com.wildcreator.wildsearch.data.model.BochaJson
import com.wildcreator.wildsearch.data.model.BochaResponse
import com.wildcreator.wildsearch.data.model.SearchRequest
import com.wildcreator.wildsearch.data.model.SearchResult
import com.wildcreator.wildsearch.data.model.WebPageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * 博查 Web Search API 客户端。
 *
 * 只有一个端点，因此不引入 Retrofit：直接用 OkHttp + kotlinx.serialization，
 * 少一层抽象，也能拿到原始响应文本给用户核对。
 */
class BochaApi(
    private val client: OkHttpClient = defaultClient(),
    private val endpoint: String = ENDPOINT,
) {

    /**
     * 执行一次搜索。协程取消时会同步取消底层 HTTP 调用。
     *
     * @param apiKey 博查 API Key（不含 "Bearer " 前缀）。
     * @param request 请求体。
     */
    suspend fun search(apiKey: String, request: SearchRequest): SearchOutcome =
        withContext(Dispatchers.IO) {
            if (!isValidApiKey(apiKey)) return@withContext SearchOutcome.Failure(SearchError.InvalidApiKey, null)
            if (apiKey.isBlank()) return@withContext SearchOutcome.Failure(SearchError.NoApiKey, null)
            try {
                val payload = BochaJson.encodeToString(request)
                val httpRequest = Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer ${apiKey.trim()}")
                    .header("Accept", "application/json")
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val call = client.newCall(httpRequest)
                call.consume { response, context ->
                    val body = response.body.readBounded(if (response.isSuccessful) MAX_SUCCESS_BYTES else MAX_ERROR_BYTES, context)
                    if (response.isSuccessful && body.truncated) {
                        SearchOutcome.Failure(SearchError.ResponseTooLarge, rawResponsePreview(body.text, true))
                    } else {
                        interpret(response.code, body.text, body.truncated)
                    }
                }
            } catch (io: IOException) {
                coroutineContext.ensureActive()
                SearchOutcome.Failure(SearchError.Network(io.message), null)
            } catch (_: IllegalArgumentException) {
                SearchOutcome.Failure(SearchError.Malformed("请求地址或参数不合法"), null)
            }
        }

    /** A truncated error body is only a preview, never a complete JSON document. */
    private fun interpret(status: Int, body: String, truncated: Boolean): SearchOutcome {
        val parsed = if (truncated) null else runCatching {
            BochaJson.decodeFromString(BochaResponse.serializer(), body)
        }.getOrNull()
        val serverMessage = parsed?.serverMessage()
        val raw = body.takeIf { it.isNotBlank() }?.let { rawResponsePreview(it, truncated) }

        // HTTP 状态码才是成败依据：实测出现过 HTTP 400 而 body code 为 "500" 的情况。
        if (status !in 200..299) {
            val error = when (status) {
                401, 403 -> SearchError.Unauthorized(serverMessage)
                429 -> SearchError.RateLimited(serverMessage)
                in 500..599 -> SearchError.Server(status, serverMessage)
                else -> SearchError.Http(status, serverMessage)
            }
            return SearchOutcome.Failure(error, raw)
        }

        val data = parsed?.data
        if (data == null) {
            val code = parsed?.code?.asTextOrNull()
            val error = if (code != null && code != "200") {
                SearchError.Server(code.toIntOrNull() ?: status, serverMessage)
            } else {
                SearchError.Malformed(serverMessage ?: "响应缺少 data 字段")
            }
            return SearchOutcome.Failure(error, raw)
        }

        val pages = data.webPages?.value.orEmpty().mapNotNull(::toResult)
        return SearchOutcome.Success(
            results = pages,
            totalMatches = data.webPages?.totalEstimatedMatches,
            normalizedQuery = data.queryContext?.originalQuery,
            rawJson = raw.orEmpty(),
        )
    }

    /** 文案按实测优先级兜底：message -> msg -> error -> subCode。 */
    private fun BochaResponse.serverMessage(): String? =
        sequenceOf(message, msg, error, subCode)
            .firstOrNull { !it.isNullOrBlank() }
            ?.trim()

    /** code 可能是数字 200，也可能是字符串 "401"；统一成文本再比较。 */
    private fun JsonElement.asTextOrNull(): String? = when (this) {
        JsonNull -> null
        is JsonPrimitive -> contentOrNull
        else -> toString()
    }

    private fun toResult(item: WebPageItem): SearchResult? {
        val url = item.url?.trim()?.takeIf { it.startsWith("http") } ?: return null
        val title = item.name?.trim()?.takeIf { it.isNotEmpty() } ?: url
        return SearchResult(
            id = item.id?.takeIf { it.isNotBlank() } ?: url,
            title = title,
            url = url,
            displayUrl = item.displayUrl?.takeIf { it.isNotBlank() },
            snippet = item.snippet?.takeIf { it.isNotBlank() },
            summary = item.summary?.takeIf { it.isNotBlank() },
            siteName = item.siteName?.takeIf { it.isNotBlank() },
            siteIcon = item.siteIcon?.trim()?.takeIf { it.startsWith("http") },
            publishedAt = formatPublished(item.datePublished),
        )
    }

    /** 把 ISO-8601 时间转成 yyyy-MM-dd；无法解析时退回原始文本前 10 个字符。 */
    private fun formatPublished(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        try {
            return OffsetDateTime.parse(value).toLocalDate().toString()
        } catch (_: DateTimeParseException) {
            // 继续尝试纯日期
        }
        try {
            return LocalDate.parse(value.take(10)).toString()
        } catch (_: DateTimeParseException) {
            // 放弃格式化
        }
        return value.take(10)
    }

    companion object {
        const val ENDPOINT: String = "https://api.bochaai.com/v1/web-search"

        /** 响应体读取上限，避免异常响应（如带 Java 堆栈的 415/404）撑爆内存。 */
        private const val MAX_SUCCESS_BYTES: Long = 8L * 1024 * 1024
        private const val MAX_ERROR_BYTES: Long = 256L * 1024

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
