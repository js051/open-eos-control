package dev.openeos.control.ui

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import dev.openeos.control.data.CameraMediaStreamHandle
import dev.openeos.control.data.CameraMediaStreamSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.IOException

@UnstableApi
internal class CameraMediaDataSource(
    private val source: CameraMediaStreamSource,
) : BaseDataSource(false) {
    private var handle: CameraMediaStreamHandle? = null
    private var opened = false
    private var uri: Uri? = null
    private var bytesRemaining: Long? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        return try {
            uri = dataSpec.uri
            handle = runBlocking(Dispatchers.IO) { source.open(dataSpec.position) }
            bytesRemaining = handle?.bytesRemaining?.let { available ->
                if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(available, dataSpec.length)
            } ?: dataSpec.length.takeUnless { it == C.LENGTH_UNSET.toLong() }
            opened = true
            transferStarted(dataSpec)
            bytesRemaining ?: C.LENGTH_UNSET.toLong()
        } catch (error: Throwable) {
            throw IOException("Could not open camera video stream.", error)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val active = handle ?: return C.RESULT_END_OF_INPUT
        val requested = bytesRemaining?.let { minOf(length.toLong(), it).toInt() } ?: length
        return try {
            runBlocking(Dispatchers.IO) { active.read(buffer, offset, requested) }.also { count ->
                if (count > 0) {
                    bytesRemaining = bytesRemaining?.minus(count)
                    bytesTransferred(count)
                } else if (count < 0 && bytesRemaining?.let { it > 0L } == true) {
                    throw IOException("Camera video ended with $bytesRemaining bytes still expected.")
                }
            }
        } catch (error: Throwable) {
            throw IOException("Could not read camera video stream.", error)
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            handle?.close()
        } finally {
            handle = null
            uri = null
            bytesRemaining = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(private val source: CameraMediaStreamSource) : DataSource.Factory {
        override fun createDataSource(): DataSource = CameraMediaDataSource(source)
    }
}
