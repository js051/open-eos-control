package dev.openeos.control.data

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ConnectionFailureTest {
    @Test
    fun typedTransportFailuresMapWithoutExposingTheirPrivateMessages() {
        val privateDiagnostic = "TEST PRIVATE ADDRESS /path?token=TEST PRIVATE TOKEN"
        val cases = listOf(
            CameraWifiRouteUnavailableException() to ConnectionFailureReason.UNREACHABLE,
            SSLHandshakeException(privateDiagnostic) to ConnectionFailureReason.TLS_ERROR,
            UnknownHostException(privateDiagnostic) to ConnectionFailureReason.HOST_NOT_FOUND,
            SocketTimeoutException(privateDiagnostic) to ConnectionFailureReason.TIMEOUT,
            InterruptedIOException(privateDiagnostic) to ConnectionFailureReason.TIMEOUT,
            ConnectException(privateDiagnostic) to ConnectionFailureReason.UNREACHABLE,
            NoRouteToHostException(privateDiagnostic) to ConnectionFailureReason.UNREACHABLE,
        )

        cases.forEach { (failure, expected) ->
            assertEquals(expected, connectionFailureReason(failure))
            assertEquals(expected, connectionFailureReason(IllegalStateException("wrapper", failure)))
        }
    }

    @Test
    fun untypedMessagesAndGenericIllegalArgumentsAreNotAddressOrAuthenticationEvidence() {
        listOf(
            IllegalArgumentException("Invalid URL: 401 unauthorized"),
            IllegalStateException("403 forbidden: invalid password"),
            IOException("TLS timeout: hostname missing"),
            DesktopBridgeException(code = "HTTP_401", message = "Unauthorized"),
        ).forEach { failure ->
            assertEquals(ConnectionFailureReason.UNKNOWN, connectionFailureReason(failure))
        }
    }

    @Test
    fun mappingPropagatesCancellationIncludingWrappedCancellation() {
        val cancellation = CancellationException("TEST CANCELLED")

        assertSame(cancellation, runCatching { connectionFailureReason(cancellation) }.exceptionOrNull())
        assertSame(
            cancellation,
            runCatching { connectionFailureReason(IllegalStateException("wrapper", cancellation)) }.exceptionOrNull(),
        )
    }

    @Test
    fun cyclicUnknownCausesDoNotLoopForever() {
        val first = IllegalStateException("first")
        val second = IllegalStateException("second", first)
        first.initCause(second)

        assertEquals(ConnectionFailureReason.UNKNOWN, connectionFailureReason(first))
    }

    @Test
    fun bridgeCameraReasonAndOriginSurviveWrappingTogether() {
        val failure = cameraFailure(ConnectionFailureReason.AUTHENTICATION_REJECTED)
        val wrapped = IllegalStateException("SYNTHETIC PRIVATE WRAPPER", failure)

        assertEquals(
            ConnectionFailure(ConnectionFailureReason.AUTHENTICATION_REJECTED, cameraViaBridge = true),
            connectionFailure(wrapped),
        )
    }

    @Test
    fun existingBridgeHttpEvidenceCannotBorrowTheOriginFromANestedCameraFailure() {
        for (status in listOf(401, 403, 502)) {
            val failure = DesktopBridgeException(
                code = "CCAPI_DISCOVERY_FAILED", engine = "ccapi", message = "SYNTHETIC PRIVATE BODY",
                statusCode = status,
            ).apply { initCause(cameraFailure(ConnectionFailureReason.TLS_ERROR)) }

            assertEquals(ConnectionFailure(httpConnectionFailureReason(status)), connectionFailure(failure))
        }
    }

    @Test
    fun optionalReasonCannotExpandTheClosedBridgeDiscoveryContract() {
        val failures = listOf(
            cameraFailure(ConnectionFailureReason.INVALID_ADDRESS),
            cameraFailure(ConnectionFailureReason.UNKNOWN),
            DesktopBridgeException("CCAPI_DISCOVERY_FAILED", engine = "ccapi", message = "fixture", statusCode = 401,
                connectionReason = ConnectionFailureReason.TLS_ERROR),
            DesktopBridgeException("CCAPI_DISCOVERY_FAILED", engine = "ccapi", message = "fixture", statusCode = 403,
                connectionReason = ConnectionFailureReason.TLS_ERROR),
            DesktopBridgeException("OTHER_ERROR", engine = "ccapi", message = "fixture", statusCode = 502,
                connectionReason = ConnectionFailureReason.TLS_ERROR),
            DesktopBridgeException("CCAPI_DISCOVERY_FAILED", engine = "edsdk", message = "fixture", statusCode = 502,
                connectionReason = ConnectionFailureReason.TLS_ERROR),
        )
        failures.forEach { failure ->
            assertNull(failure.connectionReason)
            assertEquals(ConnectionFailure(httpConnectionFailureReason(requireNotNull(failure.statusCode))), connectionFailure(failure))
        }
    }

    @Test
    fun cancellationWinsOverCameraEvidenceAnywhereWithinTheBoundedChain() {
        val cancellation = CancellationException("SYNTHETIC CANCELLED")
        val failure = cameraFailure(ConnectionFailureReason.TIMEOUT).apply { initCause(cancellation) }

        assertSame(cancellation, runCatching { connectionFailure(failure) }.exceptionOrNull())
        assertSame(cancellation, runCatching { connectionFailureReason(failure) }.exceptionOrNull())
    }

    @Test
    fun cameraEvidenceTraversalKeepsTheExistingSixteenCauseLimit() {
        val failure = cameraFailure(ConnectionFailureReason.TLS_ERROR)
        val withinLimit = (1..15).fold(failure as Throwable) { cause, _ -> IllegalStateException("wrapper", cause) }
        val outsideLimit = IllegalStateException("wrapper", withinLimit)

        assertEquals(ConnectionFailure(ConnectionFailureReason.TLS_ERROR, cameraViaBridge = true), connectionFailure(withinLimit))
        assertEquals(ConnectionFailure(ConnectionFailureReason.UNKNOWN), connectionFailure(outsideLimit))
    }

    private fun cameraFailure(reason: ConnectionFailureReason) = DesktopBridgeException(
        code = "CCAPI_DISCOVERY_FAILED", engine = "ccapi", message = "SYNTHETIC PRIVATE BODY", statusCode = 502,
        connectionReason = reason,
    )
}
