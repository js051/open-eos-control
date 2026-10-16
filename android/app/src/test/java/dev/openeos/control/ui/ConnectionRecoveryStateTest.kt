package dev.openeos.control.ui

import dev.openeos.control.data.ConnectionFailureReason
import dev.openeos.control.data.DesktopBridgeException
import dev.openeos.control.R
import kotlinx.coroutines.CancellationException
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

    @Test fun cameraViaBridgeReasonsMapToDedicatedResourcesWithoutCredentialFields() {
        val cases = mapOf(
            ConnectionFailureReason.AUTHENTICATION_REJECTED to R.string.connection_recovery_camera_via_bridge_auth,
            ConnectionFailureReason.TLS_ERROR to R.string.connection_recovery_camera_via_bridge_tls,
            ConnectionFailureReason.HOST_NOT_FOUND to R.string.connection_recovery_camera_via_bridge_host_not_found,
            ConnectionFailureReason.TIMEOUT to R.string.connection_recovery_camera_via_bridge_timeout,
            ConnectionFailureReason.UNREACHABLE to R.string.connection_recovery_camera_via_bridge_unreachable,
            ConnectionFailureReason.HTTP_ERROR to R.string.connection_recovery_camera_via_bridge_http,
            ConnectionFailureReason.DISCOVERY_FAILED to R.string.connection_recovery_camera_via_bridge_discovery,
            ConnectionFailureReason.ENDPOINT_NOT_FOUND to R.string.connection_recovery_camera_via_bridge_endpoint,
        )
        cases.forEach { (reason, expected) ->
            assertEquals(expected, connectionRecoveryMessage(ConnectionRecovery(
                ConnectionAttemptTarget.DESKTOP_BRIDGE, reason, cameraViaBridge = true,
            )))
        }
    }

    @Test fun directCameraBridgeTokenAndUsbMappingsRemainIndependentOfCameraViaBridge() {
        assertEquals(R.string.connection_recovery_camera_auth, connectionRecoveryMessage(ConnectionRecovery(
            ConnectionAttemptTarget.CCAPI, ConnectionFailureReason.AUTHENTICATION_REJECTED,
        )))
        assertEquals(R.string.connection_recovery_bridge_auth, connectionRecoveryMessage(ConnectionRecovery(
            ConnectionAttemptTarget.DESKTOP_BRIDGE, ConnectionFailureReason.AUTHENTICATION_REJECTED,
        )))
        assertEquals(R.string.connection_recovery_http, connectionRecoveryMessage(ConnectionRecovery(
            ConnectionAttemptTarget.DESKTOP_BRIDGE, ConnectionFailureReason.HTTP_ERROR,
        )))
        assertEquals(R.string.connection_recovery_usb, connectionRecoveryMessage(ConnectionRecovery(
            ConnectionAttemptTarget.USB, ConnectionFailureReason.AUTHENTICATION_REJECTED, cameraViaBridge = true,
        )))
    }

    @Test fun recoveryCarriesOnlyBoundedCameraEvidenceAndHonorsTheConnectionTarget() {
        val failure = DesktopBridgeException(
            code = "CCAPI_DISCOVERY_FAILED", engine = "ccapi", statusCode = 502,
            message = "SYNTHETIC PRIVATE URL AND TOKEN",
            connectionReason = ConnectionFailureReason.AUTHENTICATION_REJECTED,
        )
        val wrapped = IllegalStateException("SYNTHETIC PRIVATE WRAPPER", failure)
        val recovery = connectionRecoveryFor(ConnectionAttemptTarget.DESKTOP_BRIDGE, wrapped)

        assertEquals(ConnectionRecovery(ConnectionAttemptTarget.DESKTOP_BRIDGE,
            ConnectionFailureReason.AUTHENTICATION_REJECTED, cameraViaBridge = true), recovery)
        assertFalse(recovery.toString().contains("SYNTHETIC"))
        assertFalse(connectionRecoveryFor(ConnectionAttemptTarget.CCAPI, wrapped).cameraViaBridge)
        assertFalse(connectionRecoveryFor(ConnectionAttemptTarget.USB, wrapped).cameraViaBridge)
        val cancellation = CancellationException("SYNTHETIC CANCELLED")
        failure.initCause(cancellation)
        assertSame(cancellation, runCatching {
            connectionRecoveryFor(ConnectionAttemptTarget.DESKTOP_BRIDGE, wrapped)
        }.exceptionOrNull())
    }

    @Test fun addressRecoveryCannotSwallowAWrappedCancellation() {
        val cancellation = CancellationException("SYNTHETIC CANCELLED")
        val failure = InvalidConnectionAddressException().apply { initCause(cancellation) }
        assertSame(cancellation, runCatching {
            connectionRecoveryFor(ConnectionAttemptTarget.DESKTOP_BRIDGE, failure)
        }.exceptionOrNull())
    }
}
