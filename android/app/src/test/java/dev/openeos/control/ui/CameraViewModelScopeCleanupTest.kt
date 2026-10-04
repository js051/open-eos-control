package dev.openeos.control.ui

import androidx.lifecycle.viewModelScope
import dev.openeos.control.data.CameraRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelScopeCleanupTest {
    @Test fun cleanupWaitsForTheParentAfterHttpIsIdleAndCancellationIsRequested() = runBlocking {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        val server = MockWebServer()
        val http = OkHttpClient()
        val viewModel = CameraViewModel(CameraRepository())
        val scopeJob = requireNotNull(viewModel.viewModelScope.coroutineContext[Job])
        val httpFinished = CountDownLatch(1)
        val returnFromIo = CountDownLatch(1)
        try {
            server.enqueue(MockResponse().setBody("synthetic response"))
            server.start()
            val request = Request.Builder().url(server.url("/")).build()
            val child = viewModel.viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    http.newCall(request).execute().use { it.body!!.string() }
                    httpFinished.countDown()
                    // Hold the IO continuation after OkHttp has removed its synchronous call.
                    check(returnFromIo.await(3, TimeUnit.SECONDS)) { "IO return gate was not opened" }
                }
            }
            main.scheduler.runCurrent()
            assertTrue("The HTTP call must finish before testing scope cleanup", httpFinished.await(3, TimeUnit.SECONDS))
            assertEquals(0, http.dispatcher.runningCallsCount())

            viewModel.viewModelScope.cancel()
            main.scheduler.runCurrent()
            assertTrue(scopeJob.isCancelled)
            assertFalse("HTTP idle and one Main turn do not establish parent completion", scopeJob.isCompleted)
            assertFalse(child.isCompleted)

            val cleanup = async(start = CoroutineStart.UNDISPATCHED) { viewModel.cancelAndAwaitTestScope(main) }
            assertFalse("Cleanup must wait for the cancelled IO child to return to Main", cleanup.isCompleted)
            returnFromIo.countDown()
            cleanup.await()
            assertTrue(scopeJob.isCompleted)
            assertTrue(child.isCompleted)
            assertEquals(0, http.dispatcher.runningCallsCount())
        } finally {
            returnFromIo.countDown()
            viewModel.viewModelScope.cancel()
            // Keep regression failure cleanup independent of the helper under test.
            try {
                withTimeout(3_000) {
                    while (!scopeJob.isCompleted) {
                        main.scheduler.runCurrent()
                        if (!scopeJob.isCompleted) delay(10)
                    }
                }
            } finally {
                try {
                    server.shutdown()
                    http.connectionPool.evictAll()
                } finally {
                    Dispatchers.resetMain()
                }
            }
        }
    }
}

/** Main must remain installed until every attached IO child has returned and completed. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun CameraViewModel.cancelAndAwaitTestScope(main: TestDispatcher) {
    val scopeJob = requireNotNull(viewModelScope.coroutineContext[Job])
    viewModelScope.cancel()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
    while (!scopeJob.isCompleted && System.nanoTime() < deadline) {
        main.scheduler.runCurrent()
        if (!scopeJob.isCompleted) delay(10)
    }
    main.scheduler.runCurrent()
    assertTrue("ViewModel children must finish before resetting Main", scopeJob.isCompleted)
}
