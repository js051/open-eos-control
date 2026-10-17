package dev.openeos.control.ui

import dev.openeos.control.R
import org.junit.Assert.*
import org.junit.Test

class CameraAppErrorTest {
    @Test fun everyAppErrorHasOneExactResourceToken() {
        assertEquals(CameraAppError.entries.size, CameraAppError.entries.map { it.messageToken }.toSet().size)
        for (error in CameraAppError.entries) {
            assertEquals(error.messageResource, cameraAppErrorResource(error.messageToken))
        }
    }

    @Test fun rawDiagnosticsAndSimilarMessagesAreNotTranslated() {
        for (error in CameraAppError.entries) {
            for (message in listOf("IOException: ${error.messageToken}", " ${error.messageToken}",
                "${error.messageToken}\n", error.name, error.messageToken.lowercase())) {
                assertNull(message, cameraAppErrorResource(message))
            }
        }
        assertNull(cameraAppErrorResource("Selected media size is outside the upload limit."))
        assertNull(cameraAppErrorResource("HTTP 500: synthetic camera diagnostic"))
        assertNull(cameraAppErrorResource("[OpenEOS.CameraAppError:UNKNOWN]"))
    }

    @Test fun uploadFailuresHaveSeparateResourcesAndTypedOrigin() {
        val failure = CameraAppException(CameraAppError.UPLOAD_SIZE_INVALID)
        assertSame(CameraAppError.UPLOAD_SIZE_INVALID, failure.error)
        assertEquals(R.string.camera_error_upload_size_invalid, failure.error.messageResource)
        assertTrue(failure is IllegalStateException)
        assertNull(cameraAppErrorResource(failure.message.orEmpty()))
    }

    @Test fun maintenanceUsesTheExistingWarningResource() {
        assertEquals(R.string.maintenance_session_retirement_unconfirmed,
            cameraAppErrorResource(CameraAppError.MAINTENANCE_RETIREMENT_UNCONFIRMED.messageToken))
    }
}
