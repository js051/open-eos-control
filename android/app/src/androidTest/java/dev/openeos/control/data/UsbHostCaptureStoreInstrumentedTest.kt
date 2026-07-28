package dev.openeos.control.data

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

class UsbHostCaptureStoreInstrumentedTest {
    @Test
    fun hostCaptureIsFinalizedThumbnailedDownloadedAndDeleted() = runBlocking {
        val store = AndroidUsbHostCaptureStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val filename = "instrumented-${UUID.randomUUID()}.jpg"
        val source = jpegBytes()
        val item = store.save(filename, "image", source.size.toLong()) { output ->
            output.write(source)
            source.size.toLong()
        }
        try {
            assertTrue(store.owns(item))
            assertTrue(store.list().any { it.id == item.id && it.sizeBytes == source.size.toLong() })

            val thumbnail = store.thumbnail(item)
            assertEquals("image/jpeg", thumbnail.contentType)
            assertTrue(thumbnail.bytes.size > 4)
            assertEquals(0xFF.toByte(), thumbnail.bytes[0])
            assertEquals(0xD8.toByte(), thumbnail.bytes[1])

            val downloaded = ByteArrayOutputStream()
            val progress = mutableListOf<CameraMediaTransferProgress>()
            val result = store.download(item, downloaded, progress::add)
            assertArrayEquals(source, downloaded.toByteArray())
            assertEquals(source.size.toLong(), result.bytesTransferred)
            assertEquals(source.size.toLong(), progress.last().bytesTransferred)
        } finally {
            store.delete(item)
        }
        assertFalse(store.list().any { it.id == item.id })
    }

    private fun jpegBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFF22AACC.toInt())
        }
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
