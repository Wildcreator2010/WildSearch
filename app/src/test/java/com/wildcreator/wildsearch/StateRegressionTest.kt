package com.wildcreator.wildsearch

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.wildcreator.wildsearch.data.SettingsRepository
import com.wildcreator.wildsearch.data.remote.*
import com.wildcreator.wildsearch.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class StateRegressionTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var scope: CoroutineScope
    private lateinit var settings: SettingsRepository
    private val models = ViewModelStore()
    private val valid = """{"code":200,"data":{"webPages":{"value":[{"name":"Example","url":"https://example.com"}]}}}"""
    private val apk = byteArrayOf(0x50, 0x4b, 3, 4, 0, 0, 0, 0)

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) {
            File(folder.root, "settings.preferences_pb")
        })
    }

    @After fun cleanup() { models.clear(); scope.cancel(); Dispatchers.resetMain() }
    private fun <T : ViewModel> keep(vm: T): T { models.put(vm.toString(), vm); return vm }
    private suspend fun await(predicate: () -> Boolean) { withTimeout(5000) { while (!predicate()) delay(5) } }
    private fun searchApi() = BochaApi(OkHttpClient.Builder().addInterceptor {
        Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
            .body(valid.toResponseBody()).build()
    }.build())
    private fun release(server: MockWebServer) = """{"tag_name":"v1.1.0","assets":[{"name":"test.apk","state":"uploaded","size":8,"browser_download_url":"${server.url("/download")}"}]}"""
    private fun update(server: MockWebServer, auto: Boolean = false) = keep(UpdateViewModel(settings,
        UpdateRepository(folder.root, repo = "demo/demo", apiBase = server.url("/").toString()), "1.0.0", auto))

    @Test fun firstEmptyAndOversizedQueriesRetryCurrentInput() = runBlocking {
        settings.updateApiKey("dummy")
        val vm = keep(SearchViewModel(settings, searchApi()))
        await { vm.state.value.ready }
        for (invalid in listOf("", "x".repeat(5001))) {
            vm.submit(invalid)
            vm.onQueryChange("hello")
            vm.retry()
            await { vm.state.value.activeQuery == "hello" && !vm.state.value.loading }
            assertNull(vm.state.value.error)
            assertEquals(1, vm.state.value.results.size)
        }
    }

    @Test fun networkRetryUsesLastSubmittedQuery() = runBlocking {
        settings.updateApiKey("dummy")
        var first = true
        val client = OkHttpClient.Builder().addInterceptor {
            if (first) { first = false; throw IOException("simulated network failure") }
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body(valid.toResponseBody()).build()
        }.build()
        val vm = keep(SearchViewModel(settings, BochaApi(client)))
        await { vm.state.value.ready }
        vm.submit("original")
        await { !vm.state.value.loading }
        assertTrue(vm.state.value.error is SearchError.Network)
        vm.onQueryChange("edited")
        vm.retry()
        await { !vm.state.value.loading }
        assertEquals("original", vm.state.value.activeQuery)
        assertNull(vm.state.value.error)
    }

    @Test fun savedKeyIsAcknowledgedOnlyAfterPersistenceAndInvalidKeyPreservesHistory() = runBlocking {
        settings.pushHistory("retained")
        val vm = keep(SearchViewModel(settings, searchApi()))
        await { vm.state.value.ready }
        vm.saveApiKey(" good-key ")
        await { vm.state.value.message != null }
        assertEquals("good-key", settings.settings.first().apiKey)
        vm.consumeMessage()
        vm.saveApiKey("bad\nkey")
        assertNotNull(vm.state.value.apiKeySaveError)
        assertNull(vm.state.value.message)
        assertEquals("good-key", settings.settings.first().apiKey)
        assertEquals(listOf("retained"), settings.settings.first().history)
        vm.saveApiKey("")
        await { vm.state.value.message != null }
        assertEquals("", settings.settings.first().apiKey)
    }

    @Test fun diskFailureDoesNotAnnounceSuccessfulSave() = runBlocking {
        val blocked = folder.newFile("not-a-directory")
        val broken = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(blocked, "settings.preferences_pb") })
        val vm = keep(SearchViewModel(broken, searchApi()))
        await { vm.state.value.ready }
        vm.saveApiKey("dummy")
        await { !vm.state.value.savingApiKey }
        assertNotNull(vm.state.value.apiKeySaveError)
        assertNull(vm.state.value.message)
        assertEquals("", vm.state.value.apiKey)
    }

    @Test fun dismissCheckingCancelsAndLateResultDoesNotReopen() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(release(server)).setBodyDelay(1, TimeUnit.SECONDS))
            val vm = update(server)
            vm.checkManually()
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            vm.dismissDialog()
            delay(1200)
            assertEquals(UpdateUiState.Phase.Idle, vm.state.value.phase)
            assertFalse(vm.state.value.dialogVisible)
        }
    }

    @Test fun deniedPermissionAndInstallerFailureReuseSameApkAndLaunchOnce() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(release(server)))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(apk)))
            val vm = update(server)
            vm.checkManually()
            await { vm.state.value.phase is UpdateUiState.Phase.Available }
            vm.startDownload()
            vm.startDownload() // Double click must not create another request.
            await { vm.state.value.phase is UpdateUiState.Phase.InstallReady }
            val first = (vm.state.value.phase as UpdateUiState.Phase.InstallReady).apk
            vm.awaitInstallPermission()
            vm.onInstallPermissionResult(false)
            assertTrue(vm.state.value.phase is UpdateUiState.Phase.PermissionRequired)
            vm.retry()
            vm.awaitInstallPermission()
            vm.onInstallPermissionResult(true)
            assertEquals(first, vm.beginInstall())
            assertNull(vm.beginInstall())
            vm.installFailed("simulated installer failure")
            assertTrue(vm.state.value.phase is UpdateUiState.Phase.InstallFailed)
            vm.retry()
            assertEquals(first, vm.beginInstall())
            vm.consumeInstall()
            assertEquals(UpdateUiState.Phase.Idle, vm.state.value.phase)
            assertTrue(first.file.isFile)
            assertEquals(2, server.requestCount) // One metadata request, one APK request.
        }
    }

    @Test fun hiddenDownloadContinuesAndManualCheckDoesNotReplaceIt() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(release(server)))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(apk)).setBodyDelay(300, TimeUnit.MILLISECONDS))
            val vm = update(server)
            vm.checkManually()
            await { vm.state.value.phase is UpdateUiState.Phase.Available }
            vm.startDownload()
            vm.dismissDialog()
            vm.checkManually()
            assertTrue(vm.state.value.phase is UpdateUiState.Phase.Downloading)
            assertFalse(vm.state.value.dialogVisible)
            await { vm.state.value.phase is UpdateUiState.Phase.InstallReady }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun lateAutomaticCheckDoesNotSupersedeManualCheck() = runBlocking {
        settings.updateAutoUpdateCheck(true)
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(release(server)))
            val vm = update(server, auto = true)
            vm.checkManually() // Mark manual operation before queued startup coroutines run.
            withTimeout(5000) {
                while (vm.state.value.phase !is UpdateUiState.Phase.Available) {
                    dispatcher.scheduler.runCurrent()
                    delay(5)
                }
            }
            assertTrue(vm.state.value.dialogVisible)
            assertEquals(1, server.requestCount)
        }
        models.clear()
        dispatcher.scheduler.runCurrent()
    }

    @Test fun foregroundAutoCheckRespectsThrottleAndRunsWhenDue() = runBlocking {
        settings.updateAutoUpdateCheck(true)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"tag_name":"v1.0.0"}"""))
            val vm = update(server, auto = true)
            await { server.requestCount == 1 && vm.state.value.phase == UpdateUiState.Phase.Idle }

            // 刚检查过：回到前台不得重复请求。
            vm.onForeground()
            delay(200)
            assertEquals(1, server.requestCount)

            // 超过 6 小时：回到前台补查一次。
            settings.markUpdateChecked(0L)
            server.enqueue(MockResponse().setBody(release(server)))
            vm.onForeground()
            await { server.requestCount == 2 && vm.state.value.phase is UpdateUiState.Phase.Available }
            assertTrue(vm.state.value.dialogVisible)
        }
    }
    @Test fun cancelledManualCheckAlsoSuppressesQueuedStartupCheck() = runBlocking {
        settings.updateAutoUpdateCheck(true)
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(release(server)))
            val vm = update(server, auto = true)
            vm.checkManually()
            vm.dismissDialog()
            repeat(100) { dispatcher.scheduler.runCurrent(); delay(10) }
            assertEquals(UpdateUiState.Phase.Idle, vm.state.value.phase)
            assertFalse(vm.state.value.dialogVisible)
            assertEquals(0, server.requestCount)
        }
        models.clear()
        dispatcher.scheduler.runCurrent()
    }

    @Test fun invalidSubmissionCancelsPriorSearchAndCannotBeOverwritten() = runBlocking {
        settings.updateApiKey("dummy")
        val entered = CompletableDeferred<Unit>()
        val gate = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor {
            entered.complete(Unit)
            gate.await(3, TimeUnit.SECONDS)
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("test").body(valid.toResponseBody()).build()
        }.build()
        val vm = keep(SearchViewModel(settings, BochaApi(client)))
        await { vm.state.value.ready }
        vm.submit("original")
        withTimeout(3000) { entered.await() }
        vm.submit("")
        gate.countDown()
        delay(100)
        assertEquals(SearchError.EmptyQuery, vm.state.value.error)
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.results.isEmpty())
    }
}
