package dev.openeos.control.ui

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** LUT-only feedback; it never owns camera-operation errors or busy flags. */
data class CubeLutImportStatus(
    val loading: Boolean = false,
    val pickerOpen: Boolean = false,
    val pickerRecoveryRequired: Boolean = false,
    val waitingForPrevious: Boolean = false,
    val stopping: Boolean = false,
    val error: String? = null,
)

internal data class CubeLutDocument(val name: String, val text: String)
internal data class CubeLutImportState(
    val lut: CubeLut? = null,
    val status: CubeLutImportStatus = CubeLutImportStatus(),
)

/**
 * Main/caller-dispatcher confined, app-lifetime import ownership. A selection gets its
 * identity before any provider I/O. There is one physical pipeline and one replaceable
 * pending selection: a provider that ignores cancellation cannot create parallel reads.
 * Cancelling revokes publication immediately, but stopping remains visible until the
 * old pipeline actually exits. Camera disconnect and sheet dismissal do not retire it.
 */
internal class CubeLutImporter(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onStateChanged: (CubeLutImportState) -> Unit,
) {
    private class Selection(val read: suspend () -> CubeLutDocument)
    private class Picker(val id: String, val selectionVersion: Long, var revoked: Boolean = false, val orphaned: Boolean = false)
    private class Work(val selection: Selection) { var job: Job? = null }
    private var owner: Selection? = null
    private var pending: Selection? = null
    private var active: Work? = null
    private var closed = false
    private var picker: Picker? = null
    private var selectionVersion = 0L
    var state: CubeLutImportState = CubeLutImportState()
        private set

    fun beginPicker(): String? {
        if (closed || picker != null || !scope.isActive) return null
        val ticket = Picker(UUID.randomUUID().toString(), selectionVersion)
        picker = ticket
        publish()
        return ticket.id
    }

    // A restored ActivityResult launch without its original ViewModel can only retire.
    // Keep its slot visible; reusing it could mistake a late result for a new selection.
    fun restorePicker(ticket: String) {
        if (!closed && picker == null) {
            picker = Picker(ticket, selectionVersion, revoked = true, orphaned = true)
            publish()
        }
    }

    fun completePicker(ticket: String, read: (suspend () -> CubeLutDocument)?) {
        val attempt = picker?.takeIf { it.id == ticket } ?: return
        picker = null
        if (!attempt.revoked && attempt.selectionVersion == selectionVersion && read != null) select(read) else publish()
    }

    fun pickerFailed(ticket: String, message: String) {
        val attempt = picker?.takeIf { it.id == ticket } ?: return
        picker = null
        if (!attempt.revoked && attempt.selectionVersion == selectionVersion) {
            retire(removeLut = false)
            publish(error = "3D LUT: $message")
        } else publish()
    }

    // null means a dismissed picker, not a new accepted selection or a failure.
    fun select(read: (suspend () -> CubeLutDocument)?) {
        if (read == null || closed || !scope.isActive) return
        selectionVersion += 1
        val selection = Selection(read)
        owner = selection
        pending = selection
        active?.job?.cancel()
        publish(error = null)
        startPending()
    }

    fun cancel() = retire(removeLut = false)
    fun remove() = retire(removeLut = true)

    fun close() {
        closed = true
        retire(removeLut = false)
    }

    private fun retire(removeLut: Boolean) {
        owner = null
        pending = null
        picker?.revoked = true
        active?.job?.cancel()
        publish(lut = if (removeLut) null else state.lut, error = null)
    }

    private fun startPending() {
        if (active != null || closed) return
        val selection = pending ?: return
        if (!scope.isActive) {
            owner = null
            pending = null
            publish()
            return
        }
        pending = null
        val work = Work(selection)
        active = work
        // Enter try/finally before the first dispatch, including cancel-before-IO.
        work.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val document = withContext(ioDispatcher) {
                    currentCoroutineContext().ensureActive()
                    selection.read()
                }
                val lut = withContext(parseDispatcher) {
                    val context = currentCoroutineContext()
                    context.ensureActive()
                    parseCubeLut(document.text, document.name) { context.ensureActive() }
                }
                currentCoroutineContext().ensureActive()
                if (owner === selection) publish(lut = lut, error = null)
            } catch (cancelled: CancellationException) {
                // Cancellation is never a parse/read failure, including after owner loss.
                throw cancelled
            } catch (error: Exception) {
                if (owner === selection) {
                    publish(error = "3D LUT: ${error.message ?: error::class.java.simpleName}")
                }
            } finally {
                if (active === work) {
                    active = null
                    if (owner === selection) owner = null
                    publish()
                    startPending()
                }
            }
        }
        if (owner !== selection) work.job?.cancel()
        publish()
    }

    private fun publish(lut: CubeLut? = state.lut, error: String? = state.status.error) {
        val retiring = active != null && active?.selection !== owner
        state = CubeLutImportState(lut, CubeLutImportStatus(
            loading = owner != null,
            pickerOpen = picker != null,
            pickerRecoveryRequired = picker?.orphaned == true,
            waitingForPrevious = pending != null && retiring,
            stopping = retiring,
            error = error,
        ))
        onStateChanged(state)
    }
}

/** Bounded byte read, then strict UTF-8. The caller owns/always closes the stream. */
internal fun readCubeLutText(input: InputStream, checkActive: () -> Unit = {}): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        checkActive()
        // Read at most one sentinel byte beyond the cap; never append oversized data.
        val remaining = MAX_CUBE_LUT_BYTES - output.size()
        val count = input.read(buffer, 0, minOf(buffer.size, remaining + 1))
        checkActive()
        if (count < 0) break
        if (count == 0) {
            // A misbehaving provider must not cause a tight zero-progress loop.
            val single = input.read()
            checkActive()
            if (single < 0) break
            require(remaining > 0) { "3D LUT exceeds the 16 MiB limit." }
            output.write(single)
        } else {
            require(count <= remaining) { "3D LUT exceeds the 16 MiB limit." }
            output.write(buffer, 0, count)
        }
    }
    checkActive()
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(output.toByteArray())).toString()
    } catch (invalid: CharacterCodingException) {
        throw IllegalArgumentException("3D LUT must be valid UTF-8.", invalid)
    }.let { decoded ->
        checkActive()
        val text = decoded.removePrefix("\uFEFF")
        require(!text.startsWith("\uFEFF")) { "3D LUT contains repeated UTF-8 byte-order marks." }
        text
    }
}
