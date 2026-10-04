package dev.openeos.control.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class CcapiMultipartLiveViewSessionTest {
    @Test fun stoppingMultipartLeavesResponseCloseWithTheReaderUntilItsReadUnwinds() = runBlocking {
        val server = MockWebServer()
        val readerEntered = CountDownLatch(1)
        val readerUnwinding = CountDownLatch(1)
        val allowReadToUnwind = CountDownLatch(1)
        val responseClosed = CountDownLatch(1)
        val readActive = AtomicBoolean()
        val closes = AtomicInteger()
        val generalStops = AtomicInteger()
        val streamStops = AtomicInteger()
        val http = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (chain.request().method != "GET" || !chain.request().url.encodedPath.endsWith("/multipart")) {
                return@addNetworkInterceptor response
            }
            val body = checkNotNull(response.body)
            val source = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    readActive.set(true)
                    readerEntered.countDown()
                    try {
                        return super.read(sink, byteCount)
                    } finally {
                        readerUnwinding.countDown()
                        // Hold the reader while it unwinds after Call.cancel(), making the
                        // close/read overlap deterministic rather than dependent on socket timing.
                        var interrupted = false
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        try {
                            while (true) {
                                try {
                                    check(allowReadToUnwind.await(
                                        maxOf(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS,
                                    )) { "Test did not release the multipart reader" }
                                    break
                                } catch (_: InterruptedException) {
                                    interrupted = true
                                }
                            }
                        } finally {
                            readActive.set(false)
                            if (interrupted) Thread.currentThread().interrupt()
                        }
                    }
                }

                override fun close() {
                    check(!readActive.get()) { "Response close overlapped the multipart reader" }
                    closes.incrementAndGet()
                    try { super.close() } finally { responseClosed.countDown() }
                }
            }.buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source(): BufferedSource = source
            }).build()
        }.build()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/ccapi" -> json("""{"ver110":[{"path":"/shooting/liveview","post":true,"delete":true},{"path":"/shooting/liveview/multipart","get":true,"delete":true}]}""")
                request.path?.endsWith("/multipart") == true && request.method == "GET" ->
                    MockResponse().setHeader("Content-Type", "multipart/x-mixed-replace; boundary=canon")
                        .setBody("--canon\r\n").setBodyDelay(2, TimeUnit.SECONDS)
                request.path?.endsWith("/multipart") == true && request.method == "DELETE" -> {
                    streamStops.incrementAndGet()
                    json()
                }
                request.path?.endsWith("/shooting/liveview") == true && request.method == "DELETE" -> {
                    generalStops.incrementAndGet()
                    json()
                }
                else -> json()
            }
        }
        server.start()
        val client = CcapiClient(server.url("/").toString(), treatAsSimulator = false, httpClient = http)
        try {
            client.initialize()
            client.startLiveView(LiveViewRequest(source = LiveViewSource.CCAPI_MULTIPART))
            assertTrue("The reader must be inside the response source", readerEntered.await(2, TimeUnit.SECONDS))
            client.stopLiveView()
            assertTrue("Call cancellation must unblock the socket reader", readerUnwinding.await(1, TimeUnit.SECONDS))
            assertEquals("Only the reader may close its response after leaving read", 0, closes.get())
            assertEquals(1, streamStops.get())
            assertEquals(1, generalStops.get())
            assertFalse(client.liveViewStopRequired)
            assertNull(client.currentLiveViewSource())
            allowReadToUnwind.countDown()
            assertTrue("The cancelled reader must release its response", responseClosed.await(2, TimeUnit.SECONDS))
            client.stopLiveView()
            assertEquals("Closing an already stopped session must be idempotent", 1, closes.get())
            assertEquals(1, generalStops.get())
        } finally {
            allowReadToUnwind.countDown()
            http.dispatcher.cancelAll()
            responseClosed.await(2, TimeUnit.SECONDS)
            client.close()
            server.shutdown()
        }
    }

    private fun json(body: String = "{}") = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
