package com.wildcreator.wildsearch

import com.wildcreator.wildsearch.data.model.SearchRequest
import com.wildcreator.wildsearch.data.remote.*
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.measureTimeMillis

class HttpRegressionTest {
    @get:Rule val folder = TemporaryFolder()
    private val apk = byteArrayOf(0x50, 0x4b, 3, 4, 0, 0, 0, 0)
    private fun info(url: String, size: Long? = 8, digest: String? = null) =
        UpdateInfo("1.1.0", "test.apk", url, size, digest, null, null, null)

    @Test fun responseOverEightMiBIsExplicitlyRejectedAndPreviewIsUtf8Safe() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("汉".repeat(3 * 1024 * 1024)))
            val result = BochaApi(endpoint = server.url("/").toString()).search("dummy", SearchRequest("hello"))
            assertTrue(result is SearchOutcome.Failure)
            assertEquals(SearchError.ResponseTooLarge, (result as SearchOutcome.Failure).error)
            val raw = requireNotNull(result.rawJson)
            assertTrue(raw.toByteArray().size <= 256 * 1024)
            assertFalse(raw.contains('\uFFFD'))
            assertTrue(raw.contains("已截断"))
        }
    }

    @Test fun hugeErrorRetainsHttpStatusRatherThanParsingTruncatedJson() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("汉".repeat(100000)))
            val result = BochaApi(endpoint = server.url("/").toString()).search("dummy", SearchRequest("hello")) as SearchOutcome.Failure
            assertTrue(result.error is SearchError.Unauthorized)
            val raw = requireNotNull(result.rawJson)
            assertTrue(raw.toByteArray().size <= 256 * 1024)
            assertFalse(raw.contains('\uFFFD'))
        }
    }

    @Test fun rawPreviewHandlesSurrogatePairsAtBoundary() {
        val result = rawResponsePreview("😀".repeat(100000))
        assertTrue(result.toByteArray().size <= 256 * 1024)
        assertFalse(result.contains('\uFFFD'))
        assertTrue(result.contains("已截断"))
    }

    @Test fun cancelDuringSearchBodyReadCancelsHttpAndDoesNotReturnNetworkFailure() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("a".repeat(10000)).throttleBody(64, 1, TimeUnit.SECONDS))
            val started = CompletableDeferred<Unit>()
            val actual = AtomicReference<Call>()
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun responseBodyStart(call: Call) { actual.set(call); started.complete(Unit) }
            }).build()
            val job = async(Dispatchers.Default) { BochaApi(client, server.url("/").toString()).search("dummy", SearchRequest("hello")) }
            withTimeout(3000) { started.await() }
            job.cancel()
            withTimeout(500) { job.join() }
            assertTrue(actual.get().isCanceled())
            assertTrue(job.isCancelled)
        }
    }

    @Test fun malformedMetadataAndDownloadUrlsAreHandled() = runBlocking {
        assertTrue(UpdateRepository(folder.root, repo = "demo/demo", apiBase = "httpx://invalid")
            .checkForUpdate("1.0.0") is UpdateCheckResult.Failed)
        for (url in listOf("httpx://invalid", "", "https://", "file:///tmp/app.apk", "https://user:pass@example.com/app.apk")) {
            val failure = runCatching { UpdateRepository(folder.root).download(info(url)) {} }.exceptionOrNull()
            assertTrue("$url: $failure", failure is UpdateException)
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"tag_name":"v2.0.0","assets":[{"name":"test.apk","state":"uploaded","browser_download_url":"httpx://invalid"}]}"""))
            assertTrue(UpdateRepository(folder.root, repo = "demo/demo", apiBase = server.url("/").toString())
                .checkForUpdate("1.0.0") is UpdateCheckResult.Failed)
        }
    }

    @Test fun cancelUpdateCheckDoesNotReturnOrdinaryFailure() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}").setBodyDelay(1, TimeUnit.SECONDS))
            val job = async(Dispatchers.Default) {
                UpdateRepository(folder.root, repo = "demo/demo", apiBase = server.url("/").toString()).checkForUpdate("1.0.0")
            }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            job.cancel()
            withTimeout(500) { job.join() }
            assertTrue(job.isCancelled)
        }
    }

    @Test fun integrityFailuresDiscardOnlyTheirOwnTemporaryFiles() = runBlocking {
        val directory = File(folder.root, "updates").apply { mkdirs() }
        val old = File(directory, "test.apk").apply { writeBytes(apk) }
        MockWebServer().use { server ->
            val repository = UpdateRepository(folder.root)
            for ((body, size, digest) in listOf(Triple(apk, 9L, null), Triple(apk, 8L, "bad-digest"), Triple("not-apk!".toByteArray(), 8L, null))) {
                server.enqueue(MockResponse().setBody(okio.Buffer().write(body)))
                assertTrue(runCatching { repository.download(info(server.url("/").toString(), size, digest)) {} }.exceptionOrNull() is UpdateException)
                assertArrayEquals(apk, old.readBytes())
                assertEquals(listOf("test.apk"), directory.listFiles()!!.map { it.name })
            }
        }
    }

    @Test fun cancelDownloadCleansPartialFileAndPreservesVerifiedCache() = runBlocking {
        val directory = File(folder.root, "updates").apply { mkdirs() }
        val old = File(directory, "test.apk").apply { writeBytes(apk) }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(okio.Buffer().write(apk)).throttleBody(4, 1, TimeUnit.SECONDS))
            val progressed = CompletableDeferred<Unit>()
            val job = async(Dispatchers.Default) {
                UpdateRepository(folder.root).download(info(server.url("/").toString())) { if (it > 0) progressed.complete(Unit) }
            }
            withTimeout(3000) { progressed.await() }
            job.cancel()
            withTimeout(500) { job.join() }
            withTimeout(3000) { while (directory.listFiles()!!.any { it.extension == "part" }) delay(5) }
            assertTrue(job.isCancelled)
            assertArrayEquals(apk, old.readBytes())
        }
    }

    @Test fun apkDownloadLongerThanFortyFiveSecondsSucceeds() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(okio.Buffer().write(apk)).setBodyDelay(46, TimeUnit.SECONDS))
            val elapsed = measureTimeMillis {
                val result = withTimeout(70000) { UpdateRepository(folder.root).download(info(server.url("/").toString())) {} }
                assertArrayEquals(apk, result.file.readBytes())
            }
            assertTrue("elapsed=$elapsed", elapsed >= 45000)
        }
    }
}
