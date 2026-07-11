package dev.openeos.control.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class LiveViewGeometryTest {
    @Test
    fun mapsTapInsidePillarboxedImageToSourceCoordinates() {
        val point = mapLiveViewTap(
            tapX = 800f,
            tapY = 450f,
            containerWidth = 1600f,
            containerHeight = 900f,
            sourceAspectRatio = 3f / 2f,
        )

        assertNotNull(point)
        assertEquals(0.5, point!!.x, 0.0001)
        assertEquals(0.5, point.y, 0.0001)
    }

    @Test
    fun ignoresTapInPillarboxArea() {
        val point = mapLiveViewTap(
            tapX = 50f,
            tapY = 450f,
            containerWidth = 1600f,
            containerHeight = 900f,
            sourceAspectRatio = 3f / 2f,
        )

        assertNull(point)
    }

    @Test
    fun mapsSourcePointBackIntoFittedImage() {
        val point = mapFocusPointToDisplay(
            focusPoint = FocusPoint(0.0, 0.0),
            containerWidth = 1600f,
            containerHeight = 900f,
            sourceAspectRatio = 3f / 2f,
        )

        assertEquals(125f, point.x, 0.001f)
        assertEquals(0f, point.y, 0.001f)
    }
}
