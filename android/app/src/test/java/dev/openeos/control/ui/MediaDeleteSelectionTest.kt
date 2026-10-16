package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraTransport
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class MediaDeleteSelectionTest {
    @Test fun frozenRosterHasExactIdsSourcesAndOriginalSelectionTokens() {
        val owner = Any()
        val items = mutableListOf(CameraMediaItem("usb-host:SYNTHETIC.JPG", "SAME.JPG", "image"))
        val tokens = mutableMapOf(items.single().id to "epoch-42")
        val selection = MediaDeleteSelection(items, MediaDeleteOwner(owner, 1), CameraTransport.USB_PTP, tokens)
        items.clear(); tokens.clear()
        assertEquals("usb-host:SYNTHETIC.JPG", selection.single().id)
        assertEquals(MediaDeleteSource.ANDROID_PRIVATE, selection.requests.single().source)
        assertEquals("epoch-42", selection.requests.single().selectionToken)
        assertFalse(selection.claimConfirmation(Any(), 1, false))
        assertFalse(selection.claimConfirmation(owner, 1, true))
        assertTrue(selection.claimConfirmation(owner, 1, false))
        assertFalse(selection.claimConfirmation(owner, 1, false))
    }
    @Test fun tokenNeverResetsWhenANewCompositionIsCreated() {
        val oldToken = newMediaDeleteSelectionToken()
        val newToken = newMediaDeleteSelectionToken()
        assertNotEquals(oldToken, newToken)
        val connection = Any()
        val receipt = MediaDeleteReceipt("old-screen", MediaDeleteOwner(connection, 1), listOf(
            MediaDeleteRow(MediaDeleteRequest("A", "A.JPG", MediaDeleteSource.CAMERA_CARD, selectionToken = oldToken),
                dev.openeos.control.data.MediaDeleteOutcome.CONFIRMED)))
        val selectedAgain = mapOf("A" to newToken)
        assertEquals(selectedAgain, consumeConfirmedDeleteSelection(selectedAgain, receipt, connection, 1))
    }
    @Test fun bridgeEndpointIsFrozenSanitizedAndRequiredForSourceAuthority() {
        val safe = dev.openeos.control.data.mediaDeleteEndpoint("https://user:secret@bridge.example:8443/private?token=SYNTHETIC#fragment")
        assertEquals("https://bridge.example:8443", safe)
        assertEquals("http://[::1]:8080", dev.openeos.control.data.mediaDeleteEndpoint("http://[::1]:8080/path"))
        assertNull(dev.openeos.control.data.mediaDeleteEndpoint("file:///private/source"))
        assertNull(dev.openeos.control.data.mediaDeleteEndpoint("not a URL"))
        for (endpoint in listOf(null, "https://user:secret@bridge.example", "https://bridge.example?token=SYNTHETIC")) {
            assertEquals(MediaDeleteSource.UNKNOWN, mediaDeleteSource("gphoto2-host:YWJj", CameraTransport.DESKTOP_BRIDGE, "libgphoto2", endpoint))
        }
        val frozen = MediaDeleteSelection(listOf(CameraMediaItem("gphoto2-host:YWJj", "SAME.JPG", "image")),
            MediaDeleteOwner(Any(), 1), CameraTransport.DESKTOP_BRIDGE, bridgeEngine = "libgphoto2", bridgeEndpoint = safe)
        assertEquals(MediaDeleteSource.DESKTOP_HOST, frozen.requests.single().source)
    }
    @Test fun onlyMatchingActualBridgeEngineFieldsEstablishTheProvider() {
        for (engine in listOf("libgphoto2", "ccapi")) {
            assertEquals(engine, dev.openeos.control.data.mediaDeleteBridgeEngine(engine, engine))
            for (other in listOf(null, true, 42, "auto", "unknown", "edsdk", " $engine")) {
                assertNull(dev.openeos.control.data.mediaDeleteBridgeEngine(engine, other))
                assertNull(dev.openeos.control.data.mediaDeleteBridgeEngine(other, engine))
            }
        }
        assertNull(dev.openeos.control.data.mediaDeleteBridgeEngine("ccapi", "libgphoto2"))
        assertNull(dev.openeos.control.data.mediaDeleteBridgeEngine("libgphoto2", "ccapi"))
    }
    @Test fun unknownBridgeProviderOrNamespaceNeverBecomesCameraCard() {
        for (engine in listOf(null, "auto", "unknown", "edsdk")) {
            for (id in listOf("opaque-item", "gphoto2:opaque", "gphoto2-host:YWJj", "ccapi:YWJj")) {
                assertEquals(MediaDeleteSource.UNKNOWN, mediaDeleteSource(id, CameraTransport.DESKTOP_BRIDGE, engine, "https://bridge.example:8443"))
            }
        }
        for ((engine, id) in listOf("libgphoto2" to "opaque-item", "ccapi" to "gphoto2-host:YWJj", "libgphoto2" to "ccapi:YWJj", "libgphoto2" to "gphoto2:", "libgphoto2" to "gphoto2:bad/id")) {
            assertEquals(MediaDeleteSource.UNKNOWN, mediaDeleteSource(id, CameraTransport.DESKTOP_BRIDGE, engine, "https://bridge.example:8443"))
        }
        assertEquals(MediaDeleteSource.CAMERA_CARD, mediaDeleteSource("gphoto2:WyIvRENJTSIsIkEuSlBHIl0", CameraTransport.DESKTOP_BRIDGE, "libgphoto2", "https://bridge.example:8443"))
        val syntheticCcapiPath = "/ccapi/ver100/contents/A.JPG"
        val ccapiId = "ccapi:" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(syntheticCcapiPath.toByteArray(Charsets.UTF_8))
        assertEquals(syntheticCcapiPath, String(Base64.getUrlDecoder().decode(ccapiId.substringAfter(':')), Charsets.UTF_8))
        assertEquals(MediaDeleteSource.CAMERA_CARD, mediaDeleteSource(ccapiId, CameraTransport.DESKTOP_BRIDGE, "ccapi", "https://bridge.example:8443"))
    }
    @Test fun desktopAndAndroidPrivateFilesAreNeverMislabelledAsCameraCard() {
        val syntheticFileName = "SYNTHETIC.JPG"
        val desktopId = "gphoto2-host:" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(syntheticFileName.toByteArray(Charsets.UTF_8))
        assertEquals(syntheticFileName, String(Base64.getUrlDecoder().decode(desktopId.substringAfter(':')), Charsets.UTF_8))
        assertEquals(MediaDeleteSource.DESKTOP_HOST, mediaDeleteSource(desktopId, CameraTransport.DESKTOP_BRIDGE, "libgphoto2", "https://bridge.example:8443"))
        assertEquals(MediaDeleteSource.ANDROID_PRIVATE, mediaDeleteSource("usb-host:SYNTHETIC.JPG", CameraTransport.USB_PTP))
        assertEquals(MediaDeleteSource.UNKNOWN, mediaDeleteSource("usb-host:SYNTHETIC.JPG", CameraTransport.DESKTOP_BRIDGE))
        assertEquals(MediaDeleteSource.UNKNOWN, mediaDeleteSource("gphoto2-host:SYNTHETIC.JPG", CameraTransport.CCAPI_NETWORK))
        assertEquals(MediaDeleteSource.UNKNOWN, mediaDeleteSource("42", CameraTransport.USB_PTP))
        assertEquals(MediaDeleteSource.CAMERA_CARD, mediaDeleteSource("ptp:00000042", CameraTransport.USB_PTP))
    }
}
