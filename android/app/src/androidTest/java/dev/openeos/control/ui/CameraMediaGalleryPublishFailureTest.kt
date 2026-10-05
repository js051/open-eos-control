package dev.openeos.control.ui

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.openeos.control.data.CameraMediaDownloadResult
import dev.openeos.control.data.CameraMediaItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The real ContentResolver API delegates to a local file-backed failure provider (API 29+).
 * No provider is registered, no manifest is changed, and no system MediaStore row is addressed.
 * https://developer.android.com/reference/android/content/ContentResolver#wrap(android.content.ContentProvider)
 */
@SdkSuppress(minSdkVersion = 29)
class CameraMediaGalleryPublishFailureTest {
    @Test fun failedPublicationNeverConfirmsCompletionAndCleansOnlyTheOwnedOutput() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File.createTempFile("history-publish-fixture", ".tmp", context.cacheDir).apply {
            check(delete())
            check(mkdir())
        }
        val original = byteArrayOf(2, 7, 17, 29, 41, 71)
        val untouched = File(directory, "unrelated.bin").apply { writeBytes(byteArrayOf(11, 19)) }
        val provider = RejectPublicationProvider(File(directory, "owned.jpg"))
        try {
            // wrap redirects all resolver methods to this instance; authority matches input URIs.
            provider.attachInfo(context, ProviderInfo().apply { authority = "media"; exported = false })
            val resolver = ContentResolver.wrap(provider)
            val item = CameraMediaItem("synthetic", "SYNTHETIC.JPG", "image", sizeBytes = original.size.toLong())
            var completions = 0
            val failure = runCatching {
                CameraMediaGalleryStore(resolver).save("Synthetic failure provider", item, onFinalized = { completions++ }) { output ->
                    output.write(original)
                    CameraMediaDownloadResult(item, original.size.toLong(), "image/jpeg")
                }
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure?.message.orEmpty().contains("publish"))
            assertEquals(1, provider.insertCalls)
            assertEquals(1, provider.publishCalls)
            assertEquals(1, provider.deleteCalls)
            assertEquals(0, completions)
            assertEquals(1, provider.insertedPending)
            assertArrayEquals(original, provider.bytesAtPublication)
            assertFalse(provider.destination.exists())
            assertArrayEquals(byteArrayOf(11, 19), untouched.readBytes())
        } finally {
            provider.shutdown()
            directory.deleteRecursively()
        }
    }

    private class RejectPublicationProvider(val destination: File) : ContentProvider() {
        var insertCalls = 0
        var publishCalls = 0
        var deleteCalls = 0
        var insertedPending: Int? = null
        var bytesAtPublication: ByteArray? = null
        private var ownedUri: Uri? = null

        override fun onCreate(): Boolean = true
        override fun getType(uri: Uri): String = "image/jpeg"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            insertCalls++
            insertedPending = values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING)
            return ContentUris.withAppendedId(uri, 17L).also { ownedUri = it }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            check(uri == ownedUri)
            return ParcelFileDescriptor.open(destination,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            check(uri == ownedUri)
            check(values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 0)
            publishCalls++
            bytesAtPublication = destination.readBytes()
            return 0
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            check(uri == ownedUri)
            deleteCalls++
            return if (destination.delete()) 1 else 0
        }
    }
}
