package com.wildcreator.wildsearch.data.remote

import com.wildcreator.wildsearch.data.model.SearchResult

/** 面向用户的可读错误。每个分支都自带一句中文提示，UI 不再做二次映射。 */
sealed class SearchError(val userMessage: String) {

    data object EmptyQuery : SearchError("请输入搜索关键词。")

    data object NoApiKey : SearchError("尚未配置 API Key，请先在设置中填入博查 API Key。")

    data object InvalidApiKey : SearchError("API Key 只能包含可打印的 ASCII 字符，不能包含空白或中文。")

    data object ResponseTooLarge : SearchError("服务端响应超过 8 MiB，请减少结果条数或关闭长摘要后重试。")

    /**
     * 客户端负载保护，不是接口限制。
     * 网上流传的「query 最多 400 字符」查无官方来源，故不据此拦截；
     * 真正超限交由服务端返回 400，并原样展示服务端提示。
     */
    data object QueryTooLong : SearchError("关键词过长，请精简到 5000 字符以内再搜索。")

    class Unauthorized(detail: String?) : SearchError(
        buildString {
            append("API Key 无效或无权限（401/403）。")
            detail?.takeIf { it.isNotBlank() }?.let { append("服务端提示：").append(it) }
            append(" 请到 open.bochaai.com 检查密钥状态。")
        },
    )

    class RateLimited(detail: String?) : SearchError(
        buildString {
            append("请求过于频繁或额度不足（429）。")
            detail?.takeIf { it.isNotBlank() }?.let { append("服务端提示：").append(it) }
        },
    )

    class Http(val status: Int, detail: String?) : SearchError(
        buildString {
            append("请求失败（HTTP ").append(status).append("）。")
            detail?.takeIf { it.isNotBlank() }?.let { append("服务端提示：").append(it) }
        },
    )

    class Server(val code: Int?, detail: String?) : SearchError(
        buildString {
            append("博查服务返回错误")
            code?.let { append("（code=").append(it).append("）") }
            append("。")
            detail?.takeIf { it.isNotBlank() }?.let { append("提示：").append(it) }
        },
    )

    class Network(detail: String?) : SearchError(
        buildString {
            append("网络异常，无法连接博查服务。")
            detail?.takeIf { it.isNotBlank() }?.let { append("原因：").append(it) }
        },
    )

    class Malformed(detail: String?) : SearchError(
        buildString {
            append("响应解析失败。")
            detail?.takeIf { it.isNotBlank() }?.let { append("原因：").append(it) }
        },
    )
}

/** 搜索调用的两种终态。rawJson 保留有界预览，方便在 App 内核对接口行为。 */
sealed interface SearchOutcome {

    data class Success(
        val results: List<SearchResult>,
        val totalMatches: Long?,
        val normalizedQuery: String?,
        val rawJson: String,
    ) : SearchOutcome

    data class Failure(
        val error: SearchError,
        val rawJson: String?,
    ) : SearchOutcome
}
