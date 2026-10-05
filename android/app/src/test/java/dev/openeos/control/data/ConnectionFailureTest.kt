package dev.openeos.control.data

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
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
}
