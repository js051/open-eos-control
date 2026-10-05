package dev.openeos.control.ui

import dev.openeos.control.data.ConnectionFailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRecoveryStateTest {
    @Test fun addressValidationRetainsHttpHttpsAndExistingBasePaths() {
        for (target in listOf(ConnectionAttemptTarget.CCAPI, ConnectionAttemptTarget.DESKTOP_BRIDGE)) {
            for (address in listOf("http://127.0.0.1:18080", "https://camera.example/base/", "http://[::1]:8080/")) {
                validateConnectionAddress(address, target)
            }
        }
    }

    @Test fun invalidAddressesReportNoPrivateInputOrMisleadingAuthenticationReason() {
        val cases = listOf(
            "not a URL" to ConnectionAttemptTarget.CCAPI,
            "file:///synthetic-private" to ConnectionAttemptTarget.CCAPI,
            "https://synthetic:fixture@camera.example/" to ConnectionAttemptTarget.DESKTOP_BRIDGE,
            "https://camera.example/?private=fixture" to ConnectionAttemptTarget.DESKTOP_BRIDGE,
            "https://camera.example/#fixture" to ConnectionAttemptTarget.DESKTOP_BRIDGE,
        )
        cases.forEach { (address, target) ->
            val failure = runCatching { validateConnectionAddress(address, target) }.exceptionOrNull()
            assertTrue(failure is InvalidConnectionAddressException)
            assertFalse(failure?.message.orEmpty().contains(address))
            assertNull(failure?.cause)
        }
    }

    @Test fun dismissingInlineRecoveryDoesNotAcknowledgePreviousShutterWarning() {
        val state = CameraUiState(
            connectionRecovery = ConnectionRecovery(ConnectionAttemptTarget.CCAPI, ConnectionFailureReason.TIMEOUT),
            shutterDisconnectWarning = true,
            errorOperation = CameraOperation.CONNECT,
        )
        val dismissed = state.dismissVisibleCameraMessage()
        assertNull(dismissed.connectionRecovery)
        assertNull(dismissed.errorOperation)
        assertTrue(dismissed.shutterDisconnectWarning)
        assertFalse(dismissed.dismissVisibleCameraMessage().shutterDisconnectWarning)
    }

    @Test fun unresolvedCurrentShutterReleaseCannotBeDismissedAsAConnectionProblem() {
        val state = CameraUiState(
            connectionRecovery = ConnectionRecovery(ConnectionAttemptTarget.DESKTOP_BRIDGE, ConnectionFailureReason.AUTHENTICATION_REJECTED),
            shutterReleaseUnconfirmed = true,
            error = "safe connection failure",
            errorOperation = CameraOperation.CONNECT,
        )
        assertSame(state, state.dismissVisibleCameraMessage())
        assertEquals(ConnectionFailureReason.AUTHENTICATION_REJECTED, state.connectionRecovery?.reason)
    }
}
