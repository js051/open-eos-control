package dev.openeos.control.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Real backend/session with synthetic PTP responses; no physical-camera evidence. */
class UsbPtpStorageOwnershipTest {
    @Test
    fun lateStorageSuccessCannotReplaceReconnectedStorageOrMediaWithReusedIds() = runTest {
        storageOperations.forEach { verifyLateResponse(it, PtpResponseCode.OK, Replacement.SUPPORTED) }
    }

    @Test
    fun lateStorageRejectionCannotClearReconnectedStorageOrPublishItsError() = runTest {
        storageOperations.forEach { verifyLateResponse(it, PtpResponseCode.DEVICE_BUSY, Replacement.SUPPORTED) }
    }

    @Test
    fun lateStorageSuccessOrRejectionCannotRepopulateDisconnectedState() = runTest {
        storageOperations.forEach { operation ->
            listOf(PtpResponseCode.OK, PtpResponseCode.DEVICE_BUSY).forEach { response ->
                verifyLateResponse(operation, response, Replacement.NONE)
            }
        }
    }

    @Test
    fun lateStorageReplyCannotEnableUnsupportedStorageOnReplacement() = runTest {
        storageOperations.forEach { operation ->
            listOf(PtpResponseCode.OK, PtpResponseCode.DEVICE_BUSY).forEach { response ->
                verifyLateResponse(operation, response, Replacement.UNSUPPORTED)
            }
        }
    }

    @Test
    fun lateStorageReplyPreservesReplacementStorageRejection() = runTest {
        listOf(PtpResponseCode.OK, PtpResponseCode.DEVICE_BUSY).forEach { response ->
            verifyLateResponse(PtpOperationCode.GET_STORAGE_INFO, response, Replacement.REJECTED)
        }
    }

    @Test
    fun disconnectAfterFirstStorageInfoPreventsRequestForNextCard() = runTest {
        verifyLateResponse(
            PtpOperationCode.GET_STORAGE_INFO,
            PtpResponseCode.OK,
            Replacement.SUPPORTED,
            oldStorageIds = listOf(STORAGE, STORAGE + 1),
        )
    }

    @Test
    fun cancelledStorageRefreshDoesNotReturnStatusOrChangePreviouslyCachedStorage() = runTest {
        storageOperations.forEach { operation ->
            val wire = StorageWire("CURRENT")
            val backend = backend(wire)
            backend.initialize()
            assertTrue(backend.capabilities().matrix.supports(CameraFeature.MEDIA_UPLOAD))
            val expectedCache = cachedStorage(backend)
            val expectedFeatures = backend.observedFeatures()
            val gate = ReplyGate(operation)
            wire.beforeResponse = gate::await
            var returned = false
            var cancellation: CancellationException? = null
            val pending = launch {
                try {
                    backend.status()
                    returned = true
                } catch (exception: CancellationException) {
                    cancellation = exception
                    throw exception
                }
            }
            gate.reached.await()
            pending.cancelAndJoin()

            assertFalse("Cancelled storage refresh must not return a status", returned)
            assertTrue(cancellation is CancellationException)
            assertEquals(expectedCache, cachedStorage(backend))
            assertEquals(expectedFeatures, backend.observedFeatures())
            backend.close()
            wire.assertClosed()
        }
    }

    @Test
    fun transportCancellationIsRethrownWithoutBeingConvertedToStorageError() = runTest {
        val wire = StorageWire("CURRENT")
        val backend = backend(wire)
        backend.initialize()
        backend.capabilities()
        val expectedCache = cachedStorage(backend)
        val expectedFeatures = backend.observedFeatures()
        val cancellation = CancellationException("Synthetic storage cancellation")
        wire.beforeResponse = { if (it.code == PtpOperationCode.GET_STORAGE_INFO) throw cancellation }

        val result = runCatching { backend.status() }

        assertSame(cancellation, result.exceptionOrNull())
        assertEquals(expectedCache, cachedStorage(backend))
        assertEquals(expectedFeatures, backend.observedFeatures())
        backend.close()
        wire.assertClosed()
    }

    @Test
    fun cancellationStillWinsWhenTransportReturnsANonCancellableStorageResponse() = runTest {
        storageOperations.forEach { operation ->
            val wire = StorageWire("CURRENT")
            val backend = backend(wire)
            backend.initialize()
            backend.capabilities()
            val expectedCache = cachedStorage(backend)
            val expectedFeatures = backend.observedFeatures()
            val gate = ReplyGate(operation)
            wire.beforeResponse = { request -> withContext(NonCancellable) { gate.await(request) } }
            var returned = false
            var cancelled = false
            val pending = launch {
                try {
                    backend.status()
                    returned = true
                } catch (exception: CancellationException) {
                    cancelled = true
                    throw exception
                }
            }
            gate.reached.await()
            pending.cancel()
            gate.release.complete(Unit)
            pending.join()

            assertFalse(returned)
            assertTrue(cancelled)
            assertEquals(expectedCache, cachedStorage(backend))
            assertEquals(expectedFeatures, backend.observedFeatures())
            backend.close()
            wire.assertClosed()
        }
    }

    @Test
    fun newerListingInSameSessionDoesNotCancelValidStorageRefresh() = runTest {
        storageOperations.forEach { operation ->
            val wire = StorageWire("CURRENT")
            val backend = backend(wire)
            backend.initialize()
            val gate = ReplyGate(operation)
            wire.beforeResponse = gate::await
            val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { backend.status() } }
            gate.reached.await()
            // This retires the old listing generation before waiting for the same PTP mutex.
            val listing = async(start = CoroutineStart.UNDISPATCHED) { backend.listMedia() }
            gate.release.complete(Unit)
            val result = pending.await()
            val item = listing.await().single()

            assertTrue("A newer listing must not retire the current USB session", result.isSuccess)
            assertEquals(1, result.getOrThrow().storageDeviceCount)
            assertTrue(result.getOrThrow().rawStorageJson.contains("CURRENT"))
            assertEquals("CURRENT", cachedStorage(backend).storages.single().volumeLabel)
            assertTrue(CameraFeature.STORAGE_STATUS in backend.observedFeatures())
            assertEquals("Storage 00010001 / CURRENT", item.folder?.label)
            assertEquals(item.folder, backend.mediaPreview(item).item.folder)
            backend.close()
            wire.assertClosed()
        }
    }

    @Test
    fun currentStorageSuccessAndRejectionKeepExistingStatusSemantics() = runTest {
        val wire = StorageWire("CURRENT")
        val backend = backend(wire)
        backend.initialize()
        val successful = backend.status()
        assertEquals(true, successful.mediaAvailable)
        assertEquals(1, successful.storageDeviceCount)
        assertEquals(1_048_576L, successful.storageTotalBytes)
        assertEquals(524_288L, successful.storageFreeBytes)
        assertEquals(100L, successful.storageFreeImages)
        assertTrue(successful.rawStorageJson.contains("CURRENT"))
        assertTrue(CameraFeature.STORAGE_STATUS in backend.observedFeatures())

        wire.rejection = PtpOperationCode.GET_STORAGE_INFO to PtpResponseCode.DEVICE_BUSY
        val rejected = backend.status()
        assertEquals(false, rejected.mediaAvailable)
        assertEquals(0, rejected.storageDeviceCount)
        assertNull(rejected.storageTotalBytes)
        assertTrue(rejected.rawStorageJson.contains("DeviceBusy"))
        assertTrue(cachedStorage(backend).storages.isEmpty())
        assertTrue(cachedStorage(backend).error.orEmpty().contains("DeviceBusy"))
        backend.close()
        wire.assertClosed()
    }

    @Test
    fun currentStorageRejectionDoesNotObserveSuccessAndUnsupportedStorageIsNotRequested() = runTest {
        storageOperations.forEach { operation ->
            val wire = StorageWire("REJECTED", rejection = operation to PtpResponseCode.DEVICE_BUSY)
            val backend = backend(wire)
            backend.initialize()
            assertTrue(backend.status().rawStorageJson.contains("DeviceBusy"))
            assertFalse(CameraFeature.STORAGE_STATUS in backend.observedFeatures())
            if (operation == PtpOperationCode.GET_STORAGE_IDS) {
                assertFalse(wire.commands.any { it.first == PtpOperationCode.GET_STORAGE_INFO })
            }
            backend.close()
            wire.assertClosed()
        }
        val wire = StorageWire("UNSUPPORTED", advertiseStorage = false)
        val backend = backend(wire)
        backend.initialize()
        val status = backend.status()
        val capabilities = backend.capabilities()
        assertEquals(false, status.mediaAvailable)
        assertEquals(0, status.storageDeviceCount)
        assertEquals("[]", status.rawStorageJson)
        assertFalse(capabilities.matrix.supports(CameraFeature.STORAGE_STATUS))
        assertFalse(capabilities.matrix.supports(CameraFeature.MEDIA_UPLOAD))
        assertFalse(CameraFeature.STORAGE_STATUS in backend.observedFeatures())
        assertFalse(wire.commands.any { it.first in storageOperations })
        backend.close()
        wire.assertClosed()
    }

    private suspend fun verifyLateResponse(
        operation: Int,
        response: Int,
        replacement: Replacement,
        oldStorageIds: List<Long> = listOf(STORAGE),
    ) = coroutineScope {
        val oldWire = StorageWire("OLD", storageIds = oldStorageIds, accessCapability = 1,
            rejection = (operation to response).takeUnless { response == PtpResponseCode.OK })
        val newWire = StorageWire("NEW", advertiseStorage = replacement != Replacement.UNSUPPORTED,
            rejection = (PtpOperationCode.GET_STORAGE_INFO to PtpResponseCode.ACCESS_DENIED)
                .takeIf { replacement == Replacement.REJECTED })
        val backend = backend(oldWire, newWire)
        backend.initialize()
        val gate = ReplyGate(operation)
        oldWire.beforeResponse = gate::await
        val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { backend.status() } }
        gate.reached.await()
        // close invalidates ownership before waiting for the outstanding old PTP transaction.
        val closing = launch(start = CoroutineStart.UNDISPATCHED) { backend.close() }
        var newItem: CameraMediaItem? = null
        if (replacement != Replacement.NONE) {
            backend.initialize()
            when (replacement) {
                Replacement.SUPPORTED -> assertTrue(backend.capabilities().matrix.supports(CameraFeature.MEDIA_UPLOAD))
                Replacement.REJECTED -> assertTrue(backend.status().rawStorageJson.contains("AccessDenied"))
                else -> assertEquals(0, backend.status().storageDeviceCount)
            }
            // Deliberately reuse the storage ID, object handle and parent handle across sessions.
            newItem = backend.listMedia().single()
        }
        val expectedCache = cachedStorage(backend)
        val expectedFeatures = backend.observedFeatures()
        val replacementCommands = newWire.commands.toList()
        gate.release.complete(Unit)
        val result = pending.await()
        closing.join()

        assertTrue("Late operation $operation/$response with $replacement must cancel: $result",
            result.exceptionOrNull() is CancellationException)
        // Read the cache immediately. Calling status first could repair and hide stale writes.
        assertEquals(expectedCache, cachedStorage(backend))
        assertEquals(expectedFeatures, backend.observedFeatures())
        assertEquals(replacementCommands, newWire.commands)
        assertEquals(if (operation == PtpOperationCode.GET_STORAGE_INFO) listOf(STORAGE) else emptyList<Long>(),
            oldWire.commands.filter { it.first == PtpOperationCode.GET_STORAGE_INFO }.map { it.second.single() })
        oldWire.assertClosed()
        if (replacement != Replacement.NONE) {
            if (replacement == Replacement.SUPPORTED) {
                assertTrue(backend.capabilities().matrix.supports(CameraFeature.MEDIA_UPLOAD))
                assertEquals("Checking capabilities must reuse the intact replacement cache",
                    replacementCommands, newWire.commands)
            }
            val preview = backend.mediaPreview(requireNotNull(newItem))
            assertEquals("Storage 00010001 / NEW", preview.item.folder?.label)
            assertArrayEquals(JPEG, preview.bytes)
            backend.close()
            newWire.assertClosed()
        } else {
            assertTrue(newWire.commands.isEmpty())
        }
    }

    private enum class Replacement { NONE, SUPPORTED, UNSUPPORTED, REJECTED }

    private data class CachedStorage(val storages: List<PtpStorageInfo>, val error: String?)

    @Suppress("UNCHECKED_CAST")
    private fun cachedStorage(backend: UsbPtpCameraBackend): CachedStorage {
        fun field(name: String): Any? = UsbPtpCameraBackend::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(backend)
        return CachedStorage((field("storageSnapshot") as List<PtpStorageInfo>).toList(), field("storageError") as String?)
    }

    private fun backend(vararg wires: StorageWire): UsbPtpCameraBackend {
        val remaining = ArrayDeque(wires.toList())
        return UsbPtpCameraBackend(CameraConnection.AndroidUsbPtp("synthetic-storage-ownership"),
            PtpTransportFactory { remaining.removeFirst() })
    }

    private class ReplyGate(private val operation: Int) {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun await(request: PtpContainer) {
            if (request.code == operation && !reached.isCompleted) {
                reached.complete(Unit)
                release.await()
            }
        }
    }

    private class StorageWire(
        private val label: String,
        private val advertiseStorage: Boolean = true,
        private val storageIds: List<Long> = listOf(STORAGE),
        private val accessCapability: Int = 0,
        var rejection: Pair<Int, Int>? = null,
    ) : PtpTransport {
        val commands = mutableListOf<Pair<Int, List<Long>>>()
        private val incoming = ArrayDeque<PtpContainer>()
        private var request: PtpContainer? = null
        private var closed = false
        var beforeResponse: suspend (PtpContainer) -> Unit = {}

        override suspend fun send(container: PtpContainer) {
            check(!closed && container.type == PtpContainerType.COMMAND)
            request = container
            val parameters = container.payload.indices.step(4).map { offset ->
                (0..3).fold(0L) { value, index ->
                    value or ((container.payload[offset + index].toLong() and 0xff) shl (8 * index))
                }
            }
            commands += container.code to parameters
            rejection?.takeIf { it.first == container.code }?.let {
                incoming += PtpContainer(PtpContainerType.RESPONSE, it.second, container.transactionId)
                return
            }
            val payload = when (container.code) {
                PtpOperationCode.GET_DEVICE_INFO -> deviceInfo(advertiseStorage)
                PtpOperationCode.GET_STORAGE_IDS -> Writer().apply { array(storageIds) }.bytes()
                PtpOperationCode.GET_STORAGE_INFO -> Writer().apply {
                    check(advertiseStorage && parameters.single() in storageIds)
                    u16(4); u16(2); u16(accessCapability)
                    u64(1_048_576); u64(524_288); u32(100)
                    string("Synthetic $label"); string(label)
                }.bytes()
                PtpOperationCode.GET_OBJECT_HANDLES -> Writer().apply { array(listOf(42, 10)) }.bytes()
                PtpOperationCode.GET_OBJECT_INFO -> PtpDatasets.encodeObjectInfo(
                    if (parameters.single() == 10L) media(10, 0, label).copy(
                        objectFormat = PtpObjectFormat.ASSOCIATION, associationType = 1,
                    ) else media(42, 10, "$label.JPG"),
                )
                PtpOperationCode.GET_OBJECT -> JPEG
                PtpOperationCode.OPEN_SESSION, PtpOperationCode.CLOSE_SESSION -> null
                else -> error("Unexpected synthetic storage command ${container.code}")
            }
            if (payload != null) incoming += PtpContainer(PtpContainerType.DATA, container.code, container.transactionId, payload)
            incoming += PtpContainer(PtpContainerType.RESPONSE, PtpResponseCode.OK, container.transactionId)
        }

        override suspend fun receive(maxPayloadBytes: Int): PtpContainer {
            val container = incoming.removeFirst()
            if (container.type == PtpContainerType.RESPONSE) beforeResponse(requireNotNull(request))
            return container
        }

        override fun close() { closed = true }

        fun assertClosed() {
            assertTrue(closed)
            assertTrue(incoming.isEmpty())
            assertEquals(1, commands.count { it.first == PtpOperationCode.CLOSE_SESSION })
        }
    }

    companion object {
        private const val STORAGE = 0x00010001L
        private val storageOperations = listOf(PtpOperationCode.GET_STORAGE_IDS, PtpOperationCode.GET_STORAGE_INFO)
        private val JPEG = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 0xff.toByte(), 0xd9.toByte())

        private fun media(handle: Long, parent: Long, name: String) = PtpObjectInfo(
            handle, STORAGE, PtpObjectFormat.EXIF_JPEG, 0, JPEG.size.toLong(), 0, 0, 0, 0, 100, 100, 24,
            parent, 0, 0, 0, name, "20261011T000000", "", "",
        )

        private fun deviceInfo(advertiseStorage: Boolean) = Writer().apply {
            u16(100); u32(0); u16(100); string(""); u16(0)
            val operations = listOf(PtpOperationCode.GET_DEVICE_INFO, PtpOperationCode.OPEN_SESSION,
                PtpOperationCode.CLOSE_SESSION, PtpOperationCode.GET_STORAGE_IDS, PtpOperationCode.GET_OBJECT_HANDLES,
                PtpOperationCode.GET_OBJECT_INFO, PtpOperationCode.GET_OBJECT, PtpOperationCode.SEND_OBJECT_INFO,
                PtpOperationCode.SEND_OBJECT) + if (advertiseStorage) listOf(PtpOperationCode.GET_STORAGE_INFO) else emptyList()
            u32(operations.size.toLong()); operations.forEach(::u16)
            repeat(3) { u32(0) }
            u32(1); u16(PtpObjectFormat.EXIF_JPEG)
            string("Synthetic"); string("Storage ownership fixture"); string("1"); string("SYNTHETIC-ONLY")
        }.bytes()

        private class Writer {
            private val output = ByteArrayOutputStream()
            fun u16(value: Int) { repeat(2) { output.write(value ushr (8 * it) and 0xff) } }
            fun u32(value: Long) { repeat(4) { output.write((value ushr (8 * it) and 0xff).toInt()) } }
            fun u64(value: Long) { repeat(8) { output.write((value ushr (8 * it) and 0xff).toInt()) } }
            fun array(values: List<Long>) { u32(values.size.toLong()); values.forEach(::u32) }
            fun string(value: String) {
                if (value.isEmpty()) output.write(0) else {
                    output.write(value.length + 1); output.write(value.toByteArray(Charsets.UTF_16LE)); u16(0)
                }
            }
            fun bytes(): ByteArray = output.toByteArray()
        }
    }
}
