package dev.openeos.control.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaViewerTransformTest {
    @Test
    fun fitImageCannotPanAtOneToOneScale() {
        val viewport = IntSize(1000, 600)
        val image = IntSize(6000, 4000)

        assertEquals(Size.Zero, mediaImagePanBounds(1f, viewport, image))
        val clamped = clampMediaImageOffset(Offset(500f, -500f), 1f, viewport, image)
        assertEquals(0f, clamped.x, 0.001f)
        assertEquals(0f, clamped.y, 0.001f)
    }

    @Test
    fun zoomedImagePanIsBoundedByFittedImageEdges() {
        val viewport = IntSize(1000, 600)
        val image = IntSize(6000, 4000)

        val bounds = mediaImagePanBounds(2f, viewport, image)
        assertEquals(400f, bounds.width, 0.001f)
        assertEquals(300f, bounds.height, 0.001f)
        val clamped = clampMediaImageOffset(Offset(900f, -800f), 2f, viewport, image)
        assertEquals(400f, clamped.x, 0.001f)
        assertEquals(-300f, clamped.y, 0.001f)
    }

    @Test
    fun invalidImageGeometryNeverAllowsUnboundedPan() {
        assertEquals(Size.Zero, mediaImagePanBounds(4f, IntSize.Zero, IntSize(10, 10)))
        assertEquals(Size.Zero, mediaImagePanBounds(4f, IntSize(100, 100), IntSize.Zero))
    }
}
