package com.wildcreator.wildsearch.data.remote

import android.content.Context
import com.wildcreator.wildsearch.BuildConfig
import com.wildcreator.wildsearch.util.isNewerVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * 版本更新：查 GitHub Releases 最新版本 + 下载 APK。
 *
 * 仓库与 API 地址来自 BuildConfig（gradle.properties 里的
 * wildsearch.update.repo / wildsearch.update.apiBase），仓库未配置时
 * 检查结果为 [UpdateCheckResult.NotConfigured]，不会发任何请求。
 */
class UpdateRepository(
    private val cacheDir: File,
    private val client: OkHttpClient = BochaApi.defaultClient(),
    private val repo: String = BuildConfig.UPDATE_REPO,
    private val apiBase: String = BuildConfig.UPDATE_API_BASE,
    private val downloadClient: OkHttpClient = defaultDownloadClient(client),
) {
    constructor(context: Context, client: OkHttpClient = BochaApi.defaultClient()) :
        this(context.applicationContext.cacheDir, client)

    val configured: Boolean get() = repo.trim().contains('/')

    suspend fun checkForUpdate(currentVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (!configured) return@withContext UpdateCheckResult.NotConfigured
        try {
            val url = "${apiBase.trimEnd('/')}/repos/${repo.trim()}/releases/latest"
            val request = Request.Builder()
                .url(validUrl(url))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val call = client.newCall(request)
            call.consume { response, context ->
                val bounded = response.body.readBounded(8L * 1024 * 1024, context)
                if (bounded.truncated) return@consume UpdateCheckResult.Failed("更新信息过大，无法处理")
                val body = bounded.text
                if (!response.isSuccessful) {
                    return@consume UpdateCheckResult.Failed(
                        githubError(response.code, body, response.header("X-RateLimit-Remaining")),
                    )
                }
                val release = runCatching {
                    UpdateJson.decodeFromString(GitHubRelease.serializer(), body)
                }.getOrNull() ?: return@consume UpdateCheckResult.Failed("更新信息格式无法解析")

                if (release.draft) return@consume UpdateCheckResult.UpToDate
                val version = release.tagName?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@consume UpdateCheckResult.UpToDate
                if (!isNewerVersion(version, currentVersion)) return@consume UpdateCheckResult.UpToDate

                val asset = pickApkAsset(release.assets)
                    ?: return@consume UpdateCheckResult.Failed("新版本 $version 没有找到可下载的 APK 附件")
                validUrl(asset.downloadUrl.orEmpty())

                UpdateCheckResult.Available(
                    UpdateInfo(
                        version = version,
                        assetName = asset.name ?: "update.apk",
                        downloadUrl = asset.downloadUrl.orEmpty(),
                        sizeBytes = asset.size,
                        sha256 = asset.digest?.removePrefix("sha256:")?.takeIf { it.isNotBlank() },
                        releaseNotes = release.body?.trim()?.takeIf { it.isNotEmpty() },
                        releasePageUrl = release.htmlUrl,
                        publishedAt = release.publishedAt?.take(10),
                    ),
                )
            }
        } catch (io: IOException) {
            coroutineContext.ensureActive()
            UpdateCheckResult.Failed("网络异常，无法访问 GitHub：${io.message}")
        } catch (_: IllegalArgumentException) {
            UpdateCheckResult.Failed("更新地址不合法，仅支持 HTTP/HTTPS 地址")
        }
    }

    /**
     * 下载 APK 到应用私有缓存目录，边写边算 SHA-256。
     * 只有大小、SHA-256（release 带 digest 时）与 APK 文件头三项都通过才会返回。
     */
    suspend fun download(
        info: UpdateInfo,
        onProgress: (Float) -> Unit,
    ): DownloadedApk = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(validUrl(info.downloadUrl))
                .header("User-Agent", USER_AGENT)
                .build()
            val call = downloadClient.newCall(request)
            call.consume { response, context ->
                if (!response.isSuccessful) throw UpdateException("下载失败（HTTP ${response.code}）")
                val body = response.body ?: throw UpdateException("下载失败：响应为空")
                val directory = File(cacheDir, UPDATE_DIR)
                if (!directory.isDirectory && !directory.mkdirs()) throw UpdateException("无法创建下载缓存目录")
                // Each operation owns its temporary file; never remove an earlier verified APK.
                val temporary = File.createTempFile("wildsearch-", ".part", directory)
                try {
                    val total = body.contentLength().takeIf { it > 0 } ?: (info.sizeBytes ?: -1L)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var received = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(temporary).use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                context.ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                context.ensureActive()
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                received += read
                                if (total > 0) onProgress((received.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }

                    if (info.sizeBytes != null && info.sizeBytes > 0 && received != info.sizeBytes) {
                        throw UpdateException("文件大小不符（期望 ${info.sizeBytes}，实际 $received），已丢弃")
                    }

                    val sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                    if (!info.sha256.isNullOrBlank() && !sha256.equals(info.sha256, ignoreCase = true)) {
                        throw UpdateException("文件校验失败（SHA-256 不匹配），已丢弃")
                    }

                    if (!looksLikeApk(temporary)) {
                        throw UpdateException("下载内容不是有效的 APK，已丢弃")
                    }
                    context.ensureActive()
                    val target = File(directory, temporary.name.removeSuffix(".part") + ".apk")
                    if (!temporary.renameTo(target)) throw UpdateException("无法保存已校验的安装包")
                    onProgress(1f)
                    DownloadedApk(target, sha256)
                } finally { temporary.delete() }
            }
        } catch (io: IOException) {
            coroutineContext.ensureActive()
            throw UpdateException("下载中断：${io.message}")
        } catch (_: IllegalArgumentException) {
            throw UpdateException("下载地址不合法，仅支持 HTTP/HTTPS 地址")
        }
    }

    /** 优先 release → universal → 名字最短的 APK；忽略 .sig 等非 APK 附件。 */
    private fun pickApkAsset(assets: List<GitHubAsset>): GitHubAsset? {
        val apks = assets.filter { asset ->
            asset.state == "uploaded" &&
                !asset.downloadUrl.isNullOrBlank() &&
                asset.name?.endsWith(".apk", ignoreCase = true) == true
        }
        if (apks.isEmpty()) return null
        return apks.firstOrNull { it.name?.contains("release", ignoreCase = true) == true }
            ?: apks.firstOrNull { it.name?.contains("universal", ignoreCase = true) == true }
            ?: apks.minByOrNull { it.name?.length ?: Int.MAX_VALUE }
    }

    private fun githubError(code: Int, body: String, rateLimitRemaining: String?): String {
        val message = runCatching {
            UpdateJson.decodeFromString(GitHubError.serializer(), body).message
        }.getOrNull()?.takeIf { it.isNotBlank() }

        return when {
            code == 404 -> "更新仓库或 Release 不存在，请检查 update.repo 配置"
            code == 403 && rateLimitRemaining == "0" -> "GitHub API 访问次数已达上限，请稍后再试"
            code == 401 || code == 403 -> "GitHub 拒绝访问（HTTP $code）" + (message?.let { "：$it" } ?: "")
            else -> "检查更新失败（HTTP $code）" + (message?.let { "：$it" } ?: "")
        }
    }

    /** APK 本质是 zip，文件头必须是 PK\x03\x04。 */
    private fun looksLikeApk(file: File): Boolean {
        if (!file.isFile || file.length() < 4) return false
        return file.inputStream().use { stream ->
            val header = ByteArray(4)
            stream.read(header) == 4 &&
                header[0] == 0x50.toByte() &&
                header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() &&
                header[3] == 0x04.toByte()
        }
    }

    companion object {
        fun defaultDownloadClient(base: OkHttpClient = BochaApi.defaultClient()): OkHttpClient = base.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()

        private fun validUrl(raw: String) = raw.toHttpUrlOrNull()
            ?.takeIf { it.username.isEmpty() && it.password.isEmpty() }
            ?: throw IllegalArgumentException("Invalid update URL")

        const val UPDATE_DIR: String = "updates"

        private const val BUFFER_SIZE = 64 * 1024

        private const val USER_AGENT = "WildSearch-Android"

        private val UpdateJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}
