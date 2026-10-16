package dev.openeos.control.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

/** Formal Gradle/MockWebServer path; requires the existing project dependencies. */
class MediaDeleteHttpTest {
    @Test fun onlyComplete204ConfirmsAndOther2xxRemainUncertain() = runBlocking {
        for (code in listOf(200, 201, 202, 204, 205, 206)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(code))
            val attempt = MediaDeleteAttempt()
            runCatching { executeMediaDeleteHttp(OkHttpClient(), request(server), evidence(attempt)) }
            assertEquals(if (code == 204) MediaDeleteOutcome.CONFIRMED else MediaDeleteOutcome.UNCERTAIN, attempt.finish())
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun redirectsAndRetryableErrorsNeverSendASecondDelete() = runBlocking {
        for (code in listOf(307, 308, 408, 421, 503)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Location", server.url("/other")))
            server.enqueue(MockResponse().setResponseCode(204))
            val attempt = MediaDeleteAttempt()
            runCatching { executeMediaDeleteHttp(OkHttpClient(), request(server), evidence(attempt)) }
            assertEquals(MediaDeleteOutcome.UNCERTAIN, attempt.finish())
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun knownRefusalIsRejectedButTruncatedResponseIsUncertain() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("refused"))
            val attempt = MediaDeleteAttempt()
            runCatching { executeMediaDeleteHttp(OkHttpClient(), request(server), evidence(attempt)) }
            assertEquals(MediaDeleteOutcome.REJECTED, attempt.finish())
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("refused").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val attempt = MediaDeleteAttempt()
            runCatching { executeMediaDeleteHttp(OkHttpClient(), request(server), evidence(attempt)) }
            assertEquals(MediaDeleteOutcome.UNCERTAIN, attempt.finish())
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun preDispatchCancellationSendsNothingAndPromptCancellationPreservesAck() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val attempt = MediaDeleteAttempt().also { it.cancel() }
            runCatching { executeMediaDeleteHttp(OkHttpClient(), request(server), evidence(attempt)) }
            assertEquals(MediaDeleteOutcome.NOT_SENT, attempt.finish())
            assertNull(server.takeRequest(50, TimeUnit.MILLISECONDS))
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val attempt = MediaDeleteAttempt()
            runCatching { executeMediaDeleteHttp(OkHttpClient(), request(server), evidence(attempt).copy(onConfirmed = {
                attempt.confirm(); throw CancellationException("Synthetic cancellation after receipt")
            })) }
            assertEquals(MediaDeleteOutcome.CONFIRMED, attempt.finish())
        }
    }
    private fun request(server: MockWebServer) = Request.Builder().url(server.url("/media/exact%2Fid")).delete().build()
    private fun evidence(attempt: MediaDeleteAttempt) = MediaDeleteEvidence(attempt::beforeDispatch, attempt::confirm, attempt::reject)
}
