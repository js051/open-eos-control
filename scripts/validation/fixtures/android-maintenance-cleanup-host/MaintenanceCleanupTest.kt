package dev.openeos.control.data

import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

private class Invocation<T>(block: suspend () -> T) {
    val future = CompletableFuture<Result<T>>()
    private val job = AtomicReference<Job>()
    private val ready = CountDownLatch(1)
    init { Thread {
        try { runBlocking { job.set(currentCoroutineContext()[Job]); ready.countDown(); future.complete(runCatching { block() }) } }
        catch (_: CancellationException) { }
    }.apply { isDaemon = true; start() }; check(ready.await(1, TimeUnit.SECONDS)) }
    fun cancel() { job.get().cancel() }
    fun await(ms: Long = 2000) = future.get(ms, TimeUnit.MILLISECONDS)
}
private fun awaits(latch: CountDownLatch) { check(latch.await(25, TimeUnit.SECONDS)) }
private fun pollDone(future: CompletableFuture<*>, millis: Long): Boolean {
    val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
    while (!future.isDone && System.nanoTime() < until) Thread.sleep(5)
    return future.isDone
}
private fun cancelledCleanupProbe(liveView: Boolean, body: Boolean) {
    val client = ExtractedMaintenanceClient(liveView)
    val entered = CountDownLatch(1); val release = CountDownLatch(1)
    val target = if (liveView) "liveview/stop" else "session"
    client.respond = { request, _ ->
        if (!request.url.encodedPath.endsWith(target)) client.validResponse(request)
        else if (!body) { entered.countDown(); awaits(release); client.validResponse(request) }
        else {
            val raw = JSONObject().put("active", false).put("liveViewStopUnconfirmed", false).toString().toByteArray()
            Response(200, ResponseBody(object : ByteArrayInputStream(raw) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int { entered.countDown(); awaits(release); return super.read(bytes, offset, length) }
            }))
        }
    }
    val invocation = Invocation { client.closeMaintenanceSession() }
    try {
        assertTrue(entered.await(1, TimeUnit.SECONDS)); invocation.cancel()
        val completed = pollDone(invocation.future, 350)
        assertEquals(1L, release.count)
        assertTrue("Exact production close must return after caller cancellation while its transport/body still ignores cancel", completed)
        assertFalse(client.sessionPresent)
    } finally { release.countDown(); invocation.await() }
}
class BaselineMaintenanceCleanupTest {
    @Test(timeout=25000) fun realFifteenSecondStopDeadlineMustReturnWhileBodyIgnoresCancel() {
        val client = ExtractedMaintenanceClient(true)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        client.respond = { request, _ ->
            if (request.method == "DELETE") Response(204, null)
            else Response(200, ResponseBody(object : ByteArrayInputStream(
                JSONObject().put("active", false).put("liveViewStopUnconfirmed", false).toString().toByteArray()) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    entered.countDown(); awaits(release); return super.read(bytes, offset, length)
                }
            }))
        }
        val began = System.nanoTime()
        val invocation = Invocation { client.closeMaintenanceSession() }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            // No explicit caller cancellation and no injected short timeout. The production
            // maintenanceStopRecoveryScope's default 15-second timeout must cancel the call.
            while (client.calls.single().cancelCount.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5)
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
            assertTrue("The unmodified production deadline must issue cancellation", client.calls.single().cancelCount.get() > 0)
            assertTrue("Observed real production timeout at $elapsed ms", elapsed in 14_000..19_500)
            assertEquals(1L, release.count)
            assertTrue("After the real 15-second Stop timeout, exact production close must return before the ignored-cancel body finishes", pollDone(invocation.future, 350))
        } finally { release.countDown(); invocation.await() }
    }

    @Test(timeout=5000) fun cancelledStrictStopMustReturnWhileExecuteIgnoresCancel() = cancelledCleanupProbe(true, false)
    @Test(timeout=5000) fun cancelledSessionDeleteMustReturnWhileExecuteIgnoresCancel() = cancelledCleanupProbe(false, false)
    @Test(timeout=5000) fun cancelledBodyReadMustReturnWhileBodyIgnoresCancel() = cancelledCleanupProbe(true, true)
}

class FixedMaintenanceCleanupTest {
    @Test(timeout=5000) fun cancelledStrictStopReturnsBeforeTransport() = cancelledCleanupProbe(true, false)
    @Test(timeout=5000) fun cancelledSessionDeleteReturnsBeforeTransport() = cancelledCleanupProbe(false, false)
    @Test(timeout=5000) fun cancelledBodyReturnsBeforeTransport() = cancelledCleanupProbe(true, true)

    @Test(timeout=5000) fun strictStopThenSessionDeleteAndIndependentEventsDeleteUseOriginalRoutes() = runBlocking {
        val client = ExtractedMaintenanceClient()
        val access = client.maintenanceAccess(MaintenanceRequest(MaintenanceAction.SLEEP), null)
        access.stopEvents()
        assertFalse(client.closeMaintenanceSession())
        assertEquals(listOf("DELETE /v1/session/synthetic-session/maintenance/control/events", "POST /v1/session/synthetic-session/maintenance/control/liveview/stop", "DELETE /v1/session/synthetic-session/maintenance/control/session"), client.requests.map { "${it.method} ${it.url.encodedPath}" })
        assertFalse(client.sessionPresent)
        assertTrue(client.calls.all { it.executeCount.get() == 0 && it.enqueueCount.get() == 1 })
        assertTrue(client.calls.all { it.timeout().timeoutNanos() == TimeUnit.SECONDS.toNanos(15) })
    }

    @Test(timeout=5000) fun stopFailureStillDeletesAndRetainsStopWarning() = runBlocking {
        val client = ExtractedMaintenanceClient()
        client.respond = { request, _ -> if (request.method == "POST") Response(503, null) else Response(204, null) }
        assertTrue(client.closeMaintenanceSession())
        assertEquals(2, client.requests.size)
        assertFalse(client.sessionPresent)
    }

    @Test(timeout=5000) fun physicalFailureSkipsStrictStopAndPreservesPhysicalFailureWhenDeleteFails() = runBlocking {
        val client = ExtractedMaintenanceClient()
        val physical = IllegalArgumentException("synthetic original physical release")
        client.requirePhysicalRelease(physical)
        client.respond = { _, _ -> Response(503, null) }
        val error = runCatching { client.closeMaintenanceSession() }.exceptionOrNull()
        assertTrue(error is MaintenanceRetirementUnconfirmedException)
        error as MaintenanceRetirementUnconfirmedException
        assertTrue(error.physicalReleaseUnconfirmed); assertTrue(error.retirementUnconfirmed); assertTrue(error.stopUnconfirmed)
        assertTrue(generateSequence(error as Throwable?) { it.cause }.flatMap { it.suppressed.asSequence() }.any { it === physical })
        assertEquals(1, client.requests.size)
        assertTrue(client.requests.single().url.encodedPath.endsWith("/session"))
        assertFalse(client.sessionPresent)
    }

    @Test(timeout=5000) fun sameTextReplacementRejectsSavedEventsBeforeDispatch() = runBlocking {
        val client = ExtractedMaintenanceClient()
        val access = client.maintenanceAccess(MaintenanceRequest(MaintenanceAction.SLEEP), null)
        val fresh = client.replaceSession()
        assertTrue(runCatching { access.stopEvents() }.exceptionOrNull() is CancellationException)
        assertTrue(client.requests.isEmpty())
        assertSame(fresh, client.sessionIdentity)
    }

    @Test(timeout=5000) fun lateCloseResponseCannotClearReplacementStateOrActiveCall() {
        val client = ExtractedMaintenanceClient(false)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val response = Response(204, null)
        client.respond = { _, _ -> entered.countDown(); awaits(release); response }
        val invocation = Invocation { client.closeMaintenanceSession() }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val fresh = client.replaceSession(); val liveView = client.liveViewOwnerIdentity
            val replacementCall = HostCall(Request(HttpUrl("/synthetic-events")), { _, _ -> Response(204, null) })
            client.setActiveEvent(replacementCall)
            release.countDown()
            assertTrue(invocation.await().isFailure)
            assertSame(fresh, client.sessionIdentity); assertSame(liveView, client.liveViewOwnerIdentity)
            assertTrue(client.hasActiveEvent(replacementCall)); assertEquals(0, replacementCall.cancelCount.get())
            assertFalse(fresh.closed); assertTrue(response.closes.get() > 0)
        } finally { release.countDown(); invocation.await() }
    }

    @Test(timeout=5000) fun cancelledLateBodyCannotRunAcceptOrRearmOwner() {
        val client = ExtractedMaintenanceClient(false)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val response = Response(200, ResponseBody(object: ByteArrayInputStream(JSONObject().toString().toByteArray()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int { entered.countDown(); awaits(release); return super.read(bytes, offset, length) }
        }))
        client.respond = { _, _ -> response }
        val invocation = Invocation { client.directCleanup(onAccept=true) }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS)); invocation.cancel()
            assertTrue(pollDone(invocation.future, 350)); assertEquals(0, client.accepts.get())
            val fresh = client.replaceSession(); val liveView = client.liveViewOwnerIdentity
            release.countDown()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (response.closes.get() == 0 && System.nanoTime() < until) Thread.sleep(5)
            assertTrue(response.closes.get() > 0); assertEquals(0, client.accepts.get())
            assertSame(fresh, client.sessionIdentity); assertSame(liveView, client.liveViewOwnerIdentity)
        } finally { release.countDown(); invocation.await() }
    }

    @Test(timeout=5000) fun strictStopRequiresExplicitFalseWarningEvenWhenInactive() = runBlocking {
        for (warning in listOf<Any?>(null, true, "false", 0)) {
            val client = ExtractedMaintenanceClient()
            client.respond = { request, _ -> if (request.method == "DELETE") Response(204, null)
                else client.jsonResponse(JSONObject().put("active", false).apply { if(warning != null) put("liveViewStopUnconfirmed", warning) }) }
            assertTrue("Strict Stop must reject absent/wrong-type warning: $warning", client.closeMaintenanceSession())
            assertEquals(2, client.requests.size)
        }
    }

    @Test(timeout=5000) fun physicalFailureRemainsExactCauseWhenSessionDeleteSucceeds() = runBlocking {
        val client = ExtractedMaintenanceClient()
        val physical = IllegalArgumentException("synthetic physical failure")
        client.requirePhysicalRelease(physical)
        val error = runCatching { client.closeMaintenanceSession() }.exceptionOrNull() as MaintenanceRetirementUnconfirmedException
        assertSame(physical, error.cause)
        assertTrue(error.physicalReleaseUnconfirmed); assertFalse(error.retirementUnconfirmed); assertTrue(error.stopUnconfirmed)
        assertEquals(1, client.requests.size); assertFalse(client.sessionPresent)
    }

    @Test(timeout=5000) fun invalidCleanupFramingEncodingLengthAndSizeNeverAccept() = runBlocking {
        fun response(bytes: ByteArray = "{}".toByteArray(), length: Long = -1, headers: Map<String,List<String>> = emptyMap()) =
            Response(200, ResponseBody(ByteArrayInputStream(bytes), length), headers)
        val invalid = listOf(
            response(headers=mapOf("Content-Range" to listOf("bytes 0-1/2"))),
            response(headers=mapOf("Content-Length" to listOf("2", "2"))),
            response(headers=mapOf("Content-Length" to listOf("+2"))),
            response(headers=mapOf("Content-Length" to listOf("2"), "Transfer-Encoding" to listOf("chunked"))),
            response(headers=mapOf("Transfer-Encoding" to listOf("gzip"))),
            response(length=3),
            response(byteArrayOf(0xc3.toByte(), 0x28)),
            response("{broken".toByteArray()),
            response(ByteArray(65_537) { 32 }),
            Response(503, null),
        )
        for ((index, bad) in invalid.withIndex()) {
            val client = ExtractedMaintenanceClient(false)
            client.respond = { _, _ -> bad }
            assertTrue("Invalid strict cleanup response $index", runCatching { client.directCleanup(onAccept=true) }.isFailure)
            assertEquals(0, client.accepts.get()); assertTrue(bad.closes.get() > 0)
            assertEquals(1, client.calls.single().enqueueCount.get())
        }
    }

    @Test(timeout=5000) fun emptyDeleteRequiresTrulyEmpty204WithoutTransferEncoding() = runBlocking {
        val invalid = listOf(
            Response(204, ResponseBody(ByteArrayInputStream("{}".toByteArray()))),
            Response(204, null, mapOf("Content-Length" to listOf("1"))),
            Response(204, null, mapOf("Transfer-Encoding" to listOf("chunked"))),
            Response(200, null),
        )
        for (bad in invalid) {
            val client = ExtractedMaintenanceClient(false); client.respond = { _, _ -> bad }
            assertTrue(runCatching { client.directCleanup() }.isFailure)
            assertTrue(bad.closes.get() > 0)
        }
    }

    // Exact maintenance action GET roots now use bounded reads; the outcome child remains outside that allowlist.
    @Test(timeout=5000) fun routeSelectorUsesExactMethodOwnerAndCleanupPath() = runBlocking {
        val root = "/v1/session/synthetic-session/maintenance/"
        val controls = listOf("GET" to (root + "control/session"), "POST" to (root + "control/events"),
            "POST" to (root + "sensor-cleaning"), "GET" to (root + "sleep/outcome"),
            "POST" to (root + "control/liveview/start"),
            "DELETE" to "/v1/session/other-synthetic-session/maintenance/control/session",
            "DELETE" to (root + "control/session/extra"))
        for ((method,path) in controls) {
            val client = ExtractedMaintenanceClient(false)
            client.respond = { _, _ -> client.jsonResponse(JSONObject()) }
            client.routeProbe(method,path)
            assertEquals("Noncleanup control $method $path must retain blocking path", 1, client.calls.single().executeCount.get())
            assertEquals(0, client.calls.single().enqueueCount.get()); assertEquals(1, client.accepts.get())
        }
        val restore = ExtractedMaintenanceClient(false)
        restore.respond = { _, _ -> restore.jsonResponse(JSONObject().put("active", true).put("liveViewStopUnconfirmed", false)) }
        restore.maintenanceAccess(MaintenanceRequest(MaintenanceAction.SENSOR_CLEANING), LiveViewRequest()).restore()
        assertEquals(1, restore.calls.single().executeCount.get())
        assertTrue(restore.liveViewStopRequired)
    }

    @Test(timeout=5000) fun originalMaintenanceOwnerGateBlocksAcceptAfterCallback() {
        val client = ExtractedMaintenanceClient(false)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val originalActive = AtomicBoolean(true)
        val response = client.jsonResponse(JSONObject())
        client.respond = { _, _ -> entered.countDown(); awaits(release); response }
        val invocation = Invocation { withContext(MaintenanceIoContext(currentCoroutineContext()) {
            check(originalActive.get()) { "Synthetic original owner was retired" }
        }) { client.directCleanup(onAccept=true) } }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS)); originalActive.set(false); release.countDown()
            assertTrue(invocation.await().isFailure)
            assertEquals(0, client.accepts.get()); assertTrue(response.closes.get() > 0)
        } finally { release.countDown(); invocation.await() }
    }

    @Test(timeout=24000) fun exactProductionFifteenSecondDefaultBoundsNoCallbackAndBlockedBody() {
        val stop = ExtractedMaintenanceClient(true)
        stop.respond = { request, _ -> if(request.method == "POST") throw HostNoCallback() else Response(204,null) }
        val delete = ExtractedMaintenanceClient(false)
        delete.respond = { _, _ -> throw HostNoCallback() }
        val events = ExtractedMaintenanceClient(false)
        events.respond = { _, _ -> throw HostNoCallback() }
        val body = ExtractedMaintenanceClient(false)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val bodyResponse = Response(204, ResponseBody(object : ByteArrayInputStream(ByteArray(0)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int { entered.countDown(); awaits(release); return super.read(bytes, offset, length) }
        }))
        body.respond = { _, _ -> bodyResponse }
        val began = System.nanoTime()
        val stopping = Invocation { stop.closeMaintenanceSession() }
        val deleting = Invocation { delete.closeMaintenanceSession() }
        val stoppingEvents = Invocation { events.maintenanceAccess(MaintenanceRequest(MaintenanceAction.SLEEP), null).stopEvents() }
        val readingBody = Invocation { body.closeMaintenanceSession() }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val stopResult = stopping.await(20_000)
            val deleteResult = deleting.await(); val eventResult = stoppingEvents.await(); val bodyResult = readingBody.await()
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
            assertTrue("Unmodified production default must expire at roughly 15 seconds: $elapsed", elapsed in 14_000..19_500)
            assertEquals(1L, release.count)
            assertEquals(true, stopResult.getOrNull())
            for (result in listOf(deleteResult, eventResult, bodyResult)) assertTrue(generateSequence(result.exceptionOrNull()) { it.cause }.any { it is TimeoutCancellationException })
            assertFalse(stop.sessionPresent); assertFalse(delete.sessionPresent); assertFalse(body.sessionPresent)
            assertEquals(2, stop.requests.size)
            for(client in listOf(stop, delete, events, body)) {
                assertTrue(client.calls.all { it.executeCount.get() == 0 })
                assertTrue(client.calls.all { it.timeout().timeoutNanos() == TimeUnit.SECONDS.toNanos(15) })
            }
            assertTrue(delete.calls.single().cancelCount.get() >= 1); assertTrue(body.calls.single().cancelCount.get() >= 1)
        } finally { release.countDown() }
    }
}
