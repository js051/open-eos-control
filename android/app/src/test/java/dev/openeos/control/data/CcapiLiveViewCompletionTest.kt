package dev.openeos.control.data

import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class CcapiLiveViewCompletionTest {
    @Test fun cancellingAfterHeadersAndJpegPrefixInterruptsTheRemainingBodyRead() = runBlocking {
        val server = MockWebServer()
        val prefixRead = CountDownLatch(1)
        val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val original = requireNotNull(response.body)
                val observedSource = object : ForwardingSource(original.source()) {
                    private var consumed = 0L
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        val count = super.read(sink, byteCount)
                        if (count > 0L) consumed += count
                        if (consumed >= 2L) prefixRead.countDown()
                        return count
                    }
                }.buffer()
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = original.contentType()
                    override fun contentLength() = original.contentLength()
                    override fun source() = observedSource
                }).build()
            }.build()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
            server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg")
                .setBody(Buffer().write(jpeg)).throttleBody(2, 2, TimeUnit.SECONDS))
            val client = CcapiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString(), httpClient = http)
            val outcome = CompletableDeferred<Throwable?>()
            val delivered = AtomicBoolean(false)
            val job = launch(Dispatchers.Default) {
                outcome.complete(runCatching {
                    client.liveViewFrame(1)
                    delivered.set(true)
                }.exceptionOrNull())
            }
            try {
                assertTrue("The production reader must consume the JPEG prefix before cancellation",
                    withContext(Dispatchers.IO) { prefixRead.await(2, TimeUnit.SECONDS) })
                assertFalse(delivered.get())
                job.cancel()
                assertTrue(withTimeoutOrNull(1_000) { job.join(); true } ?: false)
                assertTrue(outcome.await() is CancellationException)
                assertFalse(delivered.get())
                assertFalse(CameraFeature.LIVE_VIEW in client.observedFeatureSnapshot())
                assertEquals(1, server.requestCount)
            } finally {
                http.dispatcher.cancelAll()
                job.cancel()
                job.join()
            }
        } finally { server.shutdown() }
    }

    @Test fun completedHttpFrameIsNotDeliveredAfterCancellationBeforeCallerResumes() {
        val server = MockWebServer()
        val httpComplete = CountDownLatch(1)
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callEnd(call: Call) { httpComplete.countDown() }
        }).build()
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val delivered = AtomicBoolean(false)
        val outcome = CompletableDeferred<Throwable?>()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
            server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(jpeg)))
            val client = CcapiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString(), httpClient = http)
            val job = scope.launch {
                outcome.complete(runCatching {
                    client.liveViewFrame(1)
                    delivered.set(true)
                }.exceptionOrNull())
            }
            dispatcher.take().run() // Start the caller and dispatch the HTTP read to Dispatchers.IO.
            assertTrue("The entire HTTP call must finish before cancellation", httpComplete.await(2, TimeUnit.SECONDS))
            val readyToReturn = dispatcher.take() // Hold the IO result before the caller dispatcher executes it.
            assertFalse(delivered.get())
            assertFalse(outcome.isCompleted)
            job.cancel()
            readyToReturn.run()
            assertTrue(job.isCompleted)
            assertTrue(outcome.isCompleted)
            runBlocking { assertTrue(outcome.await() is CancellationException) }
            assertFalse(delivered.get())
            assertFalse(CameraFeature.LIVE_VIEW in client.observedFeatureSnapshot())
            assertEquals(1, server.requestCount)
        } finally {
            scope.cancel()
            http.dispatcher.cancelAll()
            dispatcher.drain()
            server.shutdown()
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun take(): Runnable = requireNotNull(tasks.poll(2, TimeUnit.SECONDS)) { "Caller continuation was not queued." }
        fun drain() { while (true) (tasks.poll() ?: return).run() }
    }
}
