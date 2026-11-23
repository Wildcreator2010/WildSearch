package com.wildcreator.wildsearch.data.remote

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Keep the cancellation hook alive until the response has been consumed and closed. */
internal suspend fun <T> Call.consume(block: (Response, CoroutineContext) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use {
                        continuation.context.ensureActive()
                        block(it, continuation.context)
                    }
                    continuation.resume(value)
                } catch (failure: Exception) {
                    continuation.resumeWithException(failure)
                }
            }
        })
    }

internal data class BoundedBody(val text: String, val truncated: Boolean)

internal fun ResponseBody?.readBounded(limit: Long, context: CoroutineContext): BoundedBody {
    if (this == null) return BoundedBody("", false)
    val buffer = Buffer()
    val source = source()
    while (buffer.size <= limit) {
        context.ensureActive()
        if (source.read(buffer, minOf(8192L, limit + 1 - buffer.size)) == -1L) break
    }
    val truncated = buffer.size > limit
    val bytes = buffer.readByteArray(minOf(buffer.size, limit))
    return BoundedBody(decodeUtf8(bytes, truncated), truncated)
}

private fun decodeUtf8(bytes: ByteArray, truncated: Boolean): String = if (!truncated) bytes.toString(Charsets.UTF_8)
else Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.IGNORE)
    .onUnmappableCharacter(CodingErrorAction.IGNORE).decode(ByteBuffer.wrap(bytes)).toString()

/** Also used by the raw viewer/clipboard so larger successful responses stay safe to display. */
fun rawResponsePreview(text: String, alreadyTruncated: Boolean = false): String {
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (!alreadyTruncated && bytes.size <= RAW_PREVIEW_BYTES) return text
    val suffix = "\n…（响应已截断，仅预览/复制前 256 KiB）"
    val budget = RAW_PREVIEW_BYTES - suffix.toByteArray(Charsets.UTF_8).size
    return decodeUtf8(bytes.copyOfRange(0, minOf(bytes.size, budget)), true) + suffix
}

private const val RAW_PREVIEW_BYTES = 256 * 1024
