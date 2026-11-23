package com.wildcreator.wildsearch

import com.wildcreator.wildsearch.data.model.SearchRequest
import com.wildcreator.wildsearch.data.remote.BochaApi
import com.wildcreator.wildsearch.data.remote.SearchOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class BochaApiTest {
    private val valid = """{"code":200,"data":{"webPages":{"value":[{"name":"Example","url":"https://example.com"}]}}}"""
    private fun client(body: String) = OkHttpClient.Builder().addInterceptor {
        Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
            .body(body.toResponseBody()).build()
    }.build()

    @Test fun invalidKeyIsAnErrorWithoutSendingHttp() = runBlocking {
        val api = BochaApi(OkHttpClient.Builder().addInterceptor { error("Must not send invalid key") }.build())
        for (key in listOf("audit\nkey", "audit中文key", "audit key")) {
            assertTrue(api.search(key, SearchRequest("hello")) is SearchOutcome.Failure)
        }
    }

    @Test fun fiftyLongSummariesAreNotTruncated() = runBlocking {
        val entries = (1..50).joinToString(",") {
            """{"name":"Result $it","url":"https://example.com/$it","summary":"${"长".repeat(2000)}"}"""
        }
        val body = """{"code":200,"data":{"webPages":{"value":[$entries]}}}"""
        assertEquals(303276, body.toByteArray().size)
        val result = BochaApi(client(body)).search("dummy", SearchRequest("hello", count = 50))
        assertTrue(result is SearchOutcome.Success)
        assertEquals(50, (result as SearchOutcome.Success).results.size)
    }

    @Test fun cancelWhileAwaitingResponseCancelsHttpPromptly() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val gate = CountDownLatch(1)
        val call = AtomicReference<Call>()
        val client = OkHttpClient.Builder().addInterceptor {
            call.set(it.call())
            entered.complete(Unit)
            check(gate.await(3, TimeUnit.SECONDS))
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body(valid.toResponseBody()).build()
        }.build()
        val job = launch(Dispatchers.Default) { BochaApi(client).search("dummy", SearchRequest("hello")) }
        try {
            withTimeout(3000) { entered.await() }
            job.cancel()
            withTimeout(500) { job.join() }
            assertTrue(call.get().isCanceled())
        } finally { gate.countDown(); job.cancelAndJoin() }
    }
}
