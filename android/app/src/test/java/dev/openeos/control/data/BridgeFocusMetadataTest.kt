package dev.openeos.control.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BridgeFocusMetadataTest {
    @Test fun adaptsCurrentNormalizedWireAndEveryEnum() {
        for (kind in CameraFocusFrameKind.entries) for (status in CameraFocusStatus.entries) {
            val json = bridgeFocusInfoJson()
            json.getJSONArray("frames").getJSONObject(0).put("kind", kind.name).put("status", status.name)
            val result = parseBridgeFocusInfo(json)!!
            assertEquals(1, result.magnification)
            assertEquals(1.5f, result.imageAspectRatio)
            assertEquals(kind, result.frames.single().kind)
            assertEquals(if (kind == CameraFocusFrameKind.UNKNOWN) CameraFocusStatus.UNKNOWN else status, result.frames.single().status)
        }
    }

    @Test fun malformedGeometryNeverBecomesAVisibleFocusFrame() {
        for (key in listOf("left", "top", "right", "bottom")) {
            for (value in listOf("0.2", JSONObject.NULL, -0.1, 1.1, true)) {
                val json = bridgeFocusInfoJson()
                json.getJSONArray("frames").getJSONObject(0).put(key, value)
                assertNull("Reject $key=$value", parseBridgeFocusInfo(json))
            }
        }
        for (key in listOf("right", "bottom")) {
            val json = bridgeFocusInfoJson()
            json.getJSONArray("frames").getJSONObject(0).put(key, 0.05)
            assertNull(parseBridgeFocusInfo(json))
        }
        assertNull(parseBridgeFocusInfo(JSONObject(bridgeFocusInfoJson().toString().replace("0.2", "1e999"))))
    }

    @Test fun missingOrCoercedFieldsFailClosed() {
        for (key in listOf("frames", "magnification", "imageAspectRatio")) {
            val json = bridgeFocusInfoJson().apply { remove(key) }
            assertNull(parseBridgeFocusInfo(json))
        }
        for (value in listOf("1", 1.5, 0, -1, JSONObject.NULL)) {
            assertNull(parseBridgeFocusInfo(bridgeFocusInfoJson().put("magnification", value)))
        }
        for (value in listOf("1.5", 0, -1, JSONObject.NULL)) {
            assertNull(parseBridgeFocusInfo(bridgeFocusInfoJson().put("imageAspectRatio", value)))
        }
        for (key in listOf("kind", "status")) {
            val json = bridgeFocusInfoJson()
            json.getJSONArray("frames").getJSONObject(0).put(key, "focused")
            assertNull(parseBridgeFocusInfo(json))
        }
    }

    @Test fun suppressesMagnifiedGeometryAndAcceptsNoFrames() {
        assertTrue(parseBridgeFocusInfo(bridgeFocusInfoJson().put("magnification", 5))!!.frames.isEmpty())
        assertTrue(parseBridgeFocusInfo(bridgeFocusInfoJson().put("frames", JSONArray()))!!.frames.isEmpty())
    }

    @Test fun rejectsExcessiveFrameCountAtEveryMagnification() {
        for (magnification in listOf(1, 5)) {
            val json = bridgeFocusInfoJson().put("magnification", magnification)
            val frames = JSONArray()
            repeat(513) { frames.put(JSONObject()) }
            json.put("frames", frames)
            assertThrows(IllegalArgumentException::class.java) { parseBridgeFocusInfo(json) }
        }
    }
}

internal fun bridgeFocusInfoJson() = JSONObject("""{
  "frames":[{"left":0.1,"top":0.2,"right":0.4,"bottom":0.6,"status":"FOCUSED","kind":"FACE"}],
  "magnification":1,"imageAspectRatio":1.5
}""")
