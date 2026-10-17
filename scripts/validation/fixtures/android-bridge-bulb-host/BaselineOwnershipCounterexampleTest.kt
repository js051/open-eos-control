package dev.openeos.control.data

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Intentional red cases, compiled with byte-exact baseline production declarations. */
class BaselineOwnershipCounterexampleTest {
    @Test(timeout = 5000) fun pendingStartMustNotPreventMatchingStopDispatch() = runBlocking {
        val session = BridgeShutterReleaseSession("synthetic-session")
        val entered = CompletableDeferred<Unit>()
        val finishStart = CompletableDeferred<Unit>()
        val stopDispatched = CompletableDeferred<Unit>()
        val start = async(Dispatchers.Default) {
            session.start(read = { inactiveStatus() }, press = {
                entered.complete(Unit)
                finishStart.await()
                activeStatus()
            })
        }
        entered.await()
        val stop = async(Dispatchers.Default) {
            session.stop(release = { stopDispatched.complete(Unit); inactiveStatus() }, read = { inactiveStatus() })
        }
        try {
            assertNotNull("Stop must reach transport while Start's response remains pending",
                withTimeoutOrNull(250) { stopDispatched.await() })
        } finally {
            finishStart.complete(Unit)
            start.await()
            stop.await()
        }
    }

    @Test(timeout = 5000) fun statusGetMustNotProveReleaseAfterAmbiguousStop() = runBlocking {
        val session = BridgeShutterReleaseSession("synthetic-session")
        session.start(read = { inactiveStatus() }, press = { activeStatus() })
        val result = runCatching {
            session.stop(release = { throw IOException("synthetic response lost") }, read = { inactiveStatus() })
        }
        assertTrue("GET status is observation, not the exact owner terminal receipt", result.isFailure)
        assertTrue(session.hasReleaseResponsibility)
    }

    @Test(timeout = 5000) fun retiredSessionMustNotBeRearmedByLateStart() = runBlocking {
        val session = BridgeShutterReleaseSession("synthetic-session")
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val start = async(Dispatchers.Default) {
            runCatching { session.start(read = { inactiveStatus() }, press = {
                entered.complete(Unit)
                withContext(NonCancellable) { response.await() }
                activeStatus()
            }) }
        }
        entered.await()
        session.close()
        response.complete(Unit)
        start.await()
        assertFalse("Late Start must not rearm a retired session", session.hasConfirmedStart)
    }

    @Test(timeout = 5000) fun startMustUseOwnedRouteAndOneStringOwner() = runBlocking {
        val client = ExtractedDesktopBridgeClient()
        client.reply = { _, _ -> JSONObject().put("bulbExposureActive", true).put("shutterReleaseUnconfirmed", false) }
        client.startBulbExposure()
        val request = client.requests.single()
        assertEquals("/v1/session/synthetic-session/bulb/start/owned", request.path)
        assertEquals(setOf("ownerId"), request.body.keys())
        assertTrue(request.body.opt("ownerId") is String)
        assertTrue((request.body.opt("ownerId") as String).isNotBlank())
    }
}

internal fun activeStatus() = CameraStatus(bulbExposureActive = true, shutterReleaseUnconfirmed = false)
internal fun inactiveStatus() = CameraStatus(bulbExposureActive = false, shutterReleaseUnconfirmed = false)

internal data class HostRequest(val path: String, val body: JSONObject)

// Explicit synthetic boundary: only these two status fields exist; no Android models execute.
internal data class CameraStatus(val bulbExposureActive: Boolean? = null, val shutterReleaseUnconfirmed: Boolean? = null)
internal enum class CameraFeature { BULB_EXPOSURE }
internal class HostFocusSession { val hasReleaseResponsibility = false; suspend fun retryStop() {} }

// Explicit synthetic boundary: this is a typed map, not an org.json parser or wire-format test.
internal class JSONObject {
    private val fields = linkedMapOf<String, Any?>()
    fun put(key: String, value: Any?): JSONObject = apply { fields[key] = value }
    fun opt(key: String): Any? = fields[key]
    fun has(key: String) = fields.containsKey(key)
    fun keys(): Set<String> = fields.keys.toSet()
}

internal class ExtractedDesktopBridgeClient {
    private var shutterSession = BridgeShutterReleaseSession("synthetic-session")
    private val heldAutofocusSession: HostFocusSession? = null
    private val observedFeatures = mutableSetOf<CameraFeature>()
    val requests = CopyOnWriteArrayList<HostRequest>()
    var reply: suspend (String, JSONObject) -> JSONObject = { _, _ -> error("No synthetic reply") }
    private fun requireShutterSession() = shutterSession
    private fun requireCurrentSession(session: BridgeShutterReleaseSession) {
        if (shutterSession !== session || session.closed) throw CancellationException("Synthetic session changed")
    }
    private suspend fun status() = inactiveStatus()
    private suspend fun readBridgeStatus(session: BridgeShutterReleaseSession, recoveryRead: Boolean) = inactiveStatus()
    private fun endpoint(vararg segments: String) = "/" + segments.joinToString("/")
    private suspend fun postJson(path: String, body: JSONObject, shutterStart: Boolean = false): JSONObject {
        requests += HostRequest(path, body)
        return reply(path, body)
    }
    private fun parseStatus(body: JSONObject) = CameraStatus(body.opt("bulbExposureActive") as? Boolean,
        body.opt("shutterReleaseUnconfirmed") as? Boolean)

    // HOST_PRODUCTION_BULB_METHODS
}
