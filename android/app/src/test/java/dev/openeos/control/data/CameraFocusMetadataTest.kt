package dev.openeos.control.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class CameraFocusMetadataTest {
    @Test
    fun decodesDocumentedStatusAndFrameKindNibbles() {
        val statuses = listOf(0, 1, 2, 3, 4)
        val expected = listOf(CameraFocusStatus.STANDBY, CameraFocusStatus.FOCUSED, CameraFocusStatus.UNFOCUSED,
            CameraFocusStatus.SERVO_OFF, CameraFocusStatus.FOCUSING)
        for (kind in listOf(0, 0x10, 0x20, 0x30)) {
            statuses.forEachIndexed { index, status ->
                val info = parseCcapiFocusInfo(focusInfoJson(status or kind))!!
                assertEquals(expected[index], info.frames.single().status)
                assertEquals(CameraFocusFrameKind.entries[kind / 16], info.frames.single().kind)
            }
        }
    }

    @Test
    fun mapsSensorCoordinatesThroughTheCurrentCrop() {
        val frame = parseCcapiFocusInfo(focusInfoJson())!!.frames.single()
        assertEquals(0.1f, frame.left, 0.0001f)
        assertEquals(0.1f, frame.top, 0.0001f)
        assertEquals(0.3f, frame.right, 0.0001f)
        assertEquals(0.3f, frame.bottom, 0.0001f)
    }

    @Test
    fun skipsUnselectedAndNonSelectableFrames() {
        for (selection in listOf(0, 2, 3, -1)) {
            val json = focusInfoJson()
            json.getJSONObject("liveviewdata").getJSONArray("afframe").getJSONObject(0).put("select", selection)
            assertTrue(parseCcapiFocusInfo(json)!!.frames.isEmpty())
        }
    }

    @Test
    fun malformedNumericValuesDoNotBecomeFalseFocusSuccess() {
        for (invalid in listOf("1", 1.5, -1, 256, JSONObject.NULL)) {
            val json = focusInfoJson()
            json.getJSONObject("liveviewdata").getJSONArray("afframe").getJSONObject(0).put("status", invalid)
            assertTrue(parseCcapiFocusInfo(json)!!.frames.isEmpty())
        }
        for (unknown in listOf(0x81, 0x3F)) {
            assertEquals(CameraFocusStatus.UNKNOWN, parseCcapiFocusInfo(focusInfoJson(unknown))!!.frames.single().status)
        }
    }

    @Test
    fun missingGeometryAndMagnificationRemainUnavailable() {
        for (key in listOf("image", "zoom", "afframe")) {
            val json = focusInfoJson()
            json.getJSONObject("liveviewdata").remove(key)
            assertNull(parseCcapiFocusInfo(json))
        }
        val zoomed = focusInfoJson()
        zoomed.getJSONObject("liveviewdata").getJSONObject("zoom").put("magnification", 5)
        assertTrue(parseCcapiFocusInfo(zoomed)!!.frames.isEmpty())
    }

    @Test
    fun clipsPartiallyVisibleFramesAndRejectsInvalidRectangles() {
        val json = focusInfoJson()
        val frame = json.getJSONObject("liveviewdata").getJSONArray("afframe").getJSONObject(0)
        frame.put("x", 0).put("y", 0).put("width", 400).put("height", 400)
        assertEquals(0f, parseCcapiFocusInfo(json)!!.frames.single().left)
        frame.put("x", Int.MAX_VALUE).put("width", Int.MAX_VALUE)
        assertTrue(parseCcapiFocusInfo(json)!!.frames.isEmpty())
        frame.put("x", 500).put("width", -1)
        assertTrue(parseCcapiFocusInfo(json)!!.frames.isEmpty())
    }

    @Test
    fun enforcesBoundedCountAndExactBinaryEnvelope() {
        val packet = focusInfoPacket(focusInfoJson())
        assertEquals(CameraFocusStatus.FOCUSED, parseCcapiFocusInfoPacket(packet)!!.frames.single().status)
        for (bad in listOf(packet.dropLast(1).toByteArray(), packet + 0, packet.copyOf().apply { this[2] = 0 },
            packet.copyOf().apply { this[lastIndex] = 0 }, ByteArray(MAX_FOCUS_INFO_BYTES + 1))) {
            assertThrows(IllegalArgumentException::class.java) { parseCcapiFocusInfoPacket(bad) }
        }
        val json = focusInfoJson()
        val frames = JSONArray()
        repeat(513) { frames.put(JSONObject()) }
        json.getJSONObject("liveviewdata").put("afframe", frames)
        assertThrows(IllegalArgumentException::class.java) { parseCcapiFocusInfo(json) }
    }
}

internal fun focusInfoJson(status: Int = 0x21): JSONObject = JSONObject("""{
    "liveviewdata": {
      "image":{"positionx":100,"positiony":200,"positionwidth":1000,"positionheight":800},
      "zoom":{"magnification":1},
      "afframe":[{"status":$status,"select":1,"x":200,"y":280,"width":200,"height":160}]
    }
}""")

internal fun focusInfoPacket(json: JSONObject): ByteArray {
    val bytes = json.toString().toByteArray(Charsets.UTF_8)
    return ByteBuffer.allocate(bytes.size + 9).putShort(0xFF00.toShort()).put(1.toByte())
        .putInt(bytes.size).put(bytes).putShort(0xFFFF.toShort()).array()
}
