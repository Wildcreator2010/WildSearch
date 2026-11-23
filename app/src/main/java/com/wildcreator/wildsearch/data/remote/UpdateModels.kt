package com.wildcreator.wildsearch.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/**
 * GitHub Releases 结构（已对照 api.github.com 真实返回核对）。
 * 只声明用得到的字段，其余靠 ignoreUnknownKeys 忽略。
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String? = null,
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    val name: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    val size: Long? = null,
    val state: String? = null,
    val digest: String? = null,
    @SerialName("browser_download_url") val downloadUrl: String? = null,
)

@Serializable
data class GitHubError(val message: String? = null)

/** 归一化后的更新信息，UI 只认这个。 */
data class UpdateInfo(
    val version: String,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long?,
    val sha256: String?,
    val releaseNotes: String?,
    val releasePageUrl: String?,
    val publishedAt: String?,
)

data class DownloadedApk(
    val file: File,
    val sha256: String,
)

sealed interface UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data object NotConfigured : UpdateCheckResult
    data class Failed(val message: String) : UpdateCheckResult
}

class UpdateException(message: String) : Exception(message)
