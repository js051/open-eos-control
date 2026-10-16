package dev.openeos.control.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real HTTP-client fixtures, run by Gradle; these are not physical-camera evidence. */
class MediaMetadataTransportTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private suspend fun bridge(): DesktopBridgeClient {
        server.enqueue(json("""{"service":"open-eos-control-bridge"}"""))
        server.enqueue(json("""{"id":"synthetic-media-session"}"""))
        return DesktopBridgeClient(server.url("/").toString()).also { it.initialize(); repeat(2) { server.takeRequest() } }
    }
    private suspend fun canon(): CcapiClient {
        server.enqueue(json("""{"ver110":[{"path":"/contents","get":true,"put":true}]}"""))
        return CcapiClient(server.url("/").toString(), treatAsSimulator = false).also { it.initialize(); server.takeRequest() }
    }
    private val canonItem = CameraMediaItem("/ccapi/ver110/contents/card1/100EOS/SAME.JPG", "SAME.JPG", "image")
    private suspend fun DesktopBridgeClient.mutate(item: CameraMediaItem, target: MediaMetadataTarget) = when (target.field) {
        MediaMetadataField.PROTECTION -> setMediaProtection(item, target.value == 1)
        MediaMetadataField.ARCHIVE -> setMediaArchived(item, target.value == 1)
        MediaMetadataField.RATING -> setMediaRating(item, target.value)
        MediaMetadataField.ROTATION -> setMediaRotation(item, target.value)
    }
    private suspend fun CcapiClient.mutate(item: CameraMediaItem, target: MediaMetadataTarget) = when (target.field) {
        MediaMetadataField.PROTECTION -> setMediaProtection(item, target.value == 1)
        MediaMetadataField.ARCHIVE -> setMediaArchived(item, target.value == 1)
        MediaMetadataField.RATING -> setMediaRating(item, target.value)
        MediaMetadataField.ROTATION -> setMediaRotation(item, target.value)
    }
    private fun wireKey(field: MediaMetadataField) = when(field) {
        MediaMetadataField.PROTECTION -> "protected"
        MediaMetadataField.ARCHIVE -> "archived"
        MediaMetadataField.RATING -> "rating"
        MediaMetadataField.ROTATION -> "rotationDegrees"
    }
    private fun canonKey(field: MediaMetadataField) = when(field) {
        MediaMetadataField.PROTECTION -> "protect"
        MediaMetadataField.ARCHIVE -> "archive"
        MediaMetadataField.RATING -> "rating"
        MediaMetadataField.ROTATION -> "rotate"
    }
    private fun validLiteral(target: MediaMetadataTarget): String = when(target.field) {
        MediaMetadataField.PROTECTION, MediaMetadataField.ARCHIVE -> (target.value == 1).toString()
        else -> target.value.toString()
    }
    private fun canonLiteral(target: MediaMetadataTarget): String = "\"" + when(target.field) {
        MediaMetadataField.PROTECTION, MediaMetadataField.ARCHIVE -> if (target.value == 1) "enable" else "disable"
        MediaMetadataField.RATING -> if (target.value == 0) "off" else target.value.toString()
        MediaMetadataField.ROTATION -> target.value.toString()
    } + "\""
    private fun targets() = listOf(
        MediaMetadataTarget(MediaMetadataField.PROTECTION, 0), MediaMetadataTarget(MediaMetadataField.PROTECTION, 1),
        MediaMetadataTarget(MediaMetadataField.ARCHIVE, 0), MediaMetadataTarget(MediaMetadataField.ARCHIVE, 1),
    ) + (0..5).map { MediaMetadataTarget(MediaMetadataField.RATING, it) } +
        setOf(0,90,180,270).map { MediaMetadataTarget(MediaMetadataField.ROTATION, it) }

    @Test fun bridgeAllTargetsNeedExactValueInSameIdPutReadbackWithoutExtraIo() = runTest {
        val client = bridge(); val item = CameraMediaItem("exact%2FID", "SAME.JPG", "image")
        for (target in targets()) {
            server.enqueue(json("""{"id":"${item.id}","name":"SAME.JPG","kind":"image","${wireKey(target.field)}":${validLiteral(target)}}"""))
            val before = server.requestCount
            val updated = client.mutate(item, target)
            assertTrue(target.matches(item.id, updated.metadataReadback()))
            assertEquals(before + 1, server.requestCount)
            assertEquals("PUT", server.takeRequest().method)
        }
    }
    @Test fun bridgeMalformedMissingWrongIdAndMismatchedValuesNeverBecomeZeroFalseOrSuccess() = runTest {
        val client = bridge(); val item = CameraMediaItem("exact", "SAME.JPG", "image")
        for (field in MediaMetadataField.entries) {
            val target = MediaMetadataTarget(field, 0)
            val key = wireKey(field)
            val badValues = if (field == MediaMetadataField.PROTECTION || field == MediaMetadataField.ARCHIVE)
                listOf("\"false\"", "0", "null", "{}", "true") else
                listOf("false", "0.0", "0e0", "0.0000000000000000001", "\"00\"", "\"+0\"", "null", "{}", if (field == MediaMetadataField.RATING) "1" else "90")
            val bodies = badValues.map { """{"id":"exact","name":"SAME.JPG","$key":$it}""" } +
                listOf("""{"id":"exact","name":"SAME.JPG"}""",
                    """{"id":"EXACT","name":"SAME.JPG","$key":${validLiteral(target)}}""",
                    """{"name":"SAME.JPG","$key":${validLiteral(target)}}""")
            for (body in bodies) {
                server.enqueue(json(body)); val before = server.requestCount
                assertNotNull(runCatching { client.mutate(item, target) }.exceptionOrNull())
                assertEquals("No mutation retry or follow-up read", before + 1, server.requestCount)
                assertEquals("PUT", server.takeRequest().method)
            }
        }
        assertFalse(client.observedFeatureSnapshot().any { it in setOf(CameraFeature.MEDIA_PROTECT, CameraFeature.MEDIA_ARCHIVE, CameraFeature.MEDIA_RATING, CameraFeature.MEDIA_ROTATE) })
    }
    @Test fun canonicalAllTargetsBindMissingIdToExactEndpointAndRequireTypedStrings() = runTest {
        val client = canon()
        for (target in targets()) {
            server.enqueue(json("{}")); server.enqueue(json("""{"${canonKey(target.field)}":${canonLiteral(target)}}"""))
            val before = server.requestCount
            assertTrue(target.matches(canonItem.id, client.mutate(canonItem, target).metadataReadback()))
            assertEquals(before + 2, server.requestCount)
            assertEquals("PUT", server.takeRequest().method)
            assertEquals("${canonItem.id}?kind=info", server.takeRequest().path)
        }
    }
    @Test fun canonicalWrongExplicitIdOrMalformedValueUsesBoundedReadbacksAndNeverRepeatsWrite() = runTest {
        val client = canon()
        for (field in MediaMetadataField.entries) {
            val target = MediaMetadataTarget(field, 0)
            val key = canonKey(field)
            val invalid = listOf(
                """{"id":"another-card/SAME.JPG","$key":${canonLiteral(target)}}""",
                """{"id":false,"$key":${canonLiteral(target)}}""",
                """{"$key":0}""", """{"$key":false}""", """{"$key":"bad"}""", "{}",
            )
            for (body in invalid) {
                server.enqueue(json("{}")); repeat(3) { server.enqueue(json(body)) }; val before = server.requestCount
                assertNotNull(runCatching { client.mutate(canonItem, target) }.exceptionOrNull())
                assertEquals(before + 4, server.requestCount)
                assertEquals("PUT", server.takeRequest().method)
                repeat(3) { assertEquals("GET", server.takeRequest().method) }
            }
        }
    }
    @Test fun simulatorListingAndBridgeInfoKeepMalformedRotationAndFlagsUnknown() = runTest {
        val simulator = CcapiClient(server.url("/").toString(), treatAsSimulator = true)
        server.enqueue(json("""{"items":[{"id":"sim","name":"SAME.JPG","kind":"image","rotate":{},"protect":"false","archive":0,"ratingWritable":false,"protectionWritable":false,"archiveWritable":false,"rotationWritable":false}]}"""))
        val item = simulator.listMedia().single(); server.takeRequest()
        assertNull(item.rotationDegrees); assertNull(item.protected); assertNull(item.archived)
        assertEquals(false, item.ratingWritable); assertEquals(false, item.protectionWritable)
        assertEquals(false, item.archiveWritable); assertEquals(false, item.rotationWritable)
        val client = bridge()
        server.enqueue(json("""{"id":"sim","name":"SAME.JPG","rotationDegrees":{},"protected":"false","archived":0,"ratingWritable":false,"protectionWritable":false,"archiveWritable":false,"rotationWritable":false}"""))
        val updated = client.mediaInfo(item)
        assertNull(updated.rotationDegrees); assertNull(updated.protected); assertNull(updated.archived)
        assertEquals(false, updated.ratingWritable); assertEquals(false, updated.protectionWritable)
        assertEquals(false, updated.archiveWritable); assertEquals(false, updated.rotationWritable)
    }
    @Test fun partialMutationReadbackMergedIntoCurrentItemCannotClearVetoOrUnrelatedMetadata() = runTest {
        val client = bridge()
        val item = CameraMediaItem("exact", "SAME.JPG", "image", rating = 2, archived = true,
            rotationDegrees = 270, ratingWritable = false, archiveWritable = false, rotationWritable = false)
        server.enqueue(json("""{"id":"exact","name":"SAME.JPG","protected":true}"""))
        assertEquals(item.copy(protected = true), item.withVerifiedMetadata(MediaMetadataTarget(MediaMetadataField.PROTECTION, 1), client.setMediaProtection(item, true)))
        server.takeRequest()
        // A later response may even advertise broader flags; the mutation proves only its target.
        server.enqueue(json("""{"id":"exact","name":"SAME.JPG","protected":false,"ratingWritable":true,"rating":5,"archived":false}"""))
        assertEquals(item.copy(protected = false), item.withVerifiedMetadata(MediaMetadataTarget(MediaMetadataField.PROTECTION, 0), client.setMediaProtection(item, false)))
        server.takeRequest()
        val canonical = canon()
        val canonicalItem = item.copy(id = canonItem.id)
        server.enqueue(json("{}")); server.enqueue(json("""{"protect":"enable"}"""))
        assertEquals(canonicalItem.copy(protected = true), canonicalItem.withVerifiedMetadata(MediaMetadataTarget(MediaMetadataField.PROTECTION, 1), canonical.setMediaProtection(canonicalItem, true)))
    }

}
