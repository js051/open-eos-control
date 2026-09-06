package dev.openeos.control.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveViewGeometryTest {
    @Test
    fun focusCornersStayInsideImageInEveryViewport() {
        for ((width, height) in listOf(360f to 800f, 800f to 360f, 1280f to 800f, 24f to 12f)) {
            for (aspect in listOf(3f / 2f, 16f / 9f, 9f / 16f)) {
                val image = fittedLiveViewRect(width, height, aspect)
                for (point in listOf(FocusPoint(0.0, 0.0), FocusPoint(0.5, 0.5), FocusPoint(1.0, 1.0))) {
                    val frame = focusIndicatorBounds(point, width, height, aspect, 54f)
                    assertTrue(frame.left >= image.left)
                    assertTrue(frame.top >= image.top)
                    assertTrue(frame.left + frame.width <= image.left + image.width)
                    assertTrue(frame.top + frame.height <= image.top + image.height)
                    assertEquals(frame.width, frame.height, 0.001f)
                }
            }
        }
    }

    @Test
    fun focusCornersHandleZeroSize() {
        assertEquals(LiveViewRect(0f, 0f, 0f, 0f), focusIndicatorBounds(FocusPoint(0.5, 0.5), 0f, 0f, 1f, 48f))
    }

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
