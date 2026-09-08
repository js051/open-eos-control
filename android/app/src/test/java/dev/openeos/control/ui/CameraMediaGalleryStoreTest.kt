package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CameraMediaGalleryStoreTest {
    @Test fun destinationsMatchCameraConnectModelFolder() {
        assertEquals("Pictures/Canon EOS R6 Mark III/", cameraGalleryPath("Canon EOS R6 Mark III"))
        assertEquals("Pictures/Canon EOS R5/", cameraGalleryPath("Canon EOS R5"))
        assertEquals("Pictures/Open EOS Control/", cameraGalleryPath(null))
    }

    @Test fun cameraAndFileNamesCannotEscapeDestination() {
        assertEquals("_.._bad_name", safeMediaFilename("../..\\bad\nname"))
        assertFalse(safeMediaFilename("../IMG.JPG").contains('/'))
        assertEquals("camera-media", safeMediaFilename("..."))
        assertEquals(120, safeMediaFilename("a".repeat(200)).length)
    }

    @Test fun originalMimeTypesIgnoreGenericCameraResponse() {
        val item = CameraMediaItem("test", "IMG_0001.CR3", "image", contentType = "application/octet-stream")
        assertEquals("image/x-canon-cr3", galleryMimeType(item))
        assertEquals("video/mp4", galleryMimeType(item.copy(name = "MVI_0001.MP4")))
        assertEquals("image/jpeg", galleryMimeType(item.copy(name = "IMG_0001.JPG")))
        assertNull(galleryMimeType(item.copy(name = "IMG_0001.XMP")))
    }
}
