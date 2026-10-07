package dev.openeos.control.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** HTTP fixtures exercise the production client and API discovery, with no camera-device claim. */
class CcapiMediaInventoryTest {
    private data class Fixture(
        val body: String = "",
        val status: Int = 200,
        val bytes: ByteArray? = null,
        val headers: Map<String, String> = emptyMap(),
        val socketPolicy: SocketPolicy = SocketPolicy.KEEP_OPEN,
    )

    private lateinit var server: MockWebServer
    private val fixtures = ConcurrentHashMap<String, Fixture>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val root = "/ccapi/ver140/contents"
    private val photos = "$root/card1/DCIM/100CANON"
    private val movies = "$root/card1/XFVC/REEL_0001"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                val fixture = request.path?.let(fixtures::get) ?: Fixture("{}", 404)
                return MockResponse().setResponseCode(fixture.status)
                    .setHeader("Content-Type", "application/json")
                    .setBody(fixture.body)
                    .setSocketPolicy(fixture.socketPolicy)
                    .apply {
                        fixture.bytes?.let { setBody(Buffer().write(it)) }
                        fixture.headers.forEach { (name, value) -> setHeader(name, value) }
                    }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun completeInventoryTraversesEveryPageAndFolderWithoutMetadataGets() = runTest {
        val client = realClient()
        listing(root, listOf(photos, movies))
        listing(photos, listOf("$photos/IMG_0002.JPG"), listOf("$photos/IMG_0001.CR3"))
        listing(movies, listOf("$movies/MVI_0001.MP4"))

        val inventory = client.listMediaIdentities(10)

        assertTrue(inventory.complete)
        assertEquals(
            listOf("$photos/IMG_0002.JPG", "$photos/IMG_0001.CR3", "$movies/MVI_0001.MP4"),
            inventory.items.map { it.id },
        )
        assertEquals(listOf("image", "raw", "video"), inventory.items.map { it.kind })
        assertEquals(CameraMediaFolder(photos, "card1/DCIM/100CANON"), inventory.items.first().folder)
        inventory.items.forEach { assertNull(it.sizeBytes); assertNull(it.captureTime) }
        assertEquals(7, requests.size)
        assertIdentityRequestsOnly()
    }

    @Test
    fun emptyUnpagedInventoryIsComplete() = runTest {
        val client = realClient()
        fixtures["$root?kind=number"] = Fixture("""{"pagenumber":0}""")
        fixtures[root] = Fixture(paths(emptyList()))

        val inventory = client.listMediaIdentities(10)

        assertEquals(CameraMediaInventory(emptyList(), complete = true), inventory)
        assertEquals(listOf("$root?kind=number", root), requestedPaths())
    }

    @Test
    fun exactlyReachingTheCapIsIncompleteEvenOnTheLastPage() = runTest {
        val client = realClient()
        listing(root, listOf("$photos/IMG_0002.JPG", "$photos/IMG_0001.JPG"))

        val inventory = client.listMediaIdentities(2)

        assertFalse(inventory.complete)
        assertEquals(2, inventory.items.size)
        assertEquals(2, requests.size)
        assertIdentityRequestsOnly()
    }

    @Test
    fun capStopsWithinAPageAndSkipsRemainingPagesAndSiblingFolders() = runTest {
        val client = realClient()
        listing(root, listOf(photos, movies))
        listing(
            photos,
            listOf("$photos/IMG_0004.JPG"),
            listOf("$photos/IMG_0003.JPG", "$photos/IMG_0002.JPG"),
            listOf("$photos/IMG_0001.JPG"),
        )
        listing(movies, listOf("$movies/MVI_0001.MP4"))

        val inventory = client.listMediaIdentities(2)

        assertFalse(inventory.complete)
        assertEquals(listOf("IMG_0004.JPG", "IMG_0003.JPG"), inventory.items.map { it.name })
        assertEquals(5, requests.size)
        assertFalse(requestedPaths().any { it.startsWith(movies) || it.contains("page=3") })
        assertIdentityRequestsOnly()
    }

    @Test
    fun capCountsUniqueItemsAcrossSiblingFolders() = runTest {
        val client = realClient()
        val first = "$photos/IMG_0001.JPG"
        listing(root, listOf(photos, movies))
        listing(photos, listOf(first))
        listing(movies, listOf(first, "$movies/MVI_0001.MP4"))

        val inventory = client.listMediaIdentities(2)

        assertFalse(inventory.complete)
        assertEquals(listOf(first, "$movies/MVI_0001.MP4"), inventory.items.map { it.id })
        assertEquals(6, requests.size)
    }

    @Test
    fun canonicalDuplicateIdentitiesAndContainerCyclesAreVisitedOnce() = runTest {
        val client = realClient()
        val first = "$photos/IMG_0001.JPG"
        val second = "$photos/IMG_0002.JPG"
        listing(root, listOf(photos, server.url(photos).toString(), root))
        listing(
            photos,
            listOf(first, server.url(first).toString() + "?kind=main", photos, root),
            listOf(first, second),
        )

        val inventory = client.listMediaIdentities(3)

        assertTrue(inventory.complete)
        assertEquals(listOf(first, second), inventory.items.map { it.id })
        assertEquals(5, requests.size)
        assertIdentityRequestsOnly()
    }

    @Test
    fun malformedPageCountsFailInsteadOfEstablishingACompleteBaseline() = runTest {
        val malformed = listOf(
            """{"pagenumber":-1}""", """{"pagenumber":"2"}""", """{"pagenumber":1.5}""",
            """{"pagenumber":2147483648}""", """{"pagenumber":null}""",
            """{"pagenumber":true}""", "{}",
        )
        for (body in malformed) {
            val client = realClient()
            fixtures["$root?kind=number"] = Fixture(body)

            assertTrue(body, runCatching { client.listMediaIdentities(10) }.isFailure)
            assertEquals(listOf("$root?kind=number"), requestedPaths())
        }
    }

    @Test
    fun malformedPathPagesFailInsteadOfSilentlyDroppingIdentities() = runTest {
        val malformed = listOf("{}", """{"path":{}}""", """{"path":[1]}""", """{"path":[""]}""")
        for (body in malformed) {
            val client = realClient()
            listing(root, emptyList())
            fixtures["$root?page=1&order=desc"] = Fixture(body)

            assertTrue(body, runCatching { client.listMediaIdentities(10) }.isFailure)
            assertEquals(2, requests.size)
        }
    }

    @Test
    fun unsafeOrNonContentsIdentityPathsFailClosed() = runTest {
        val unsafe = listOf(
            "http://untrusted.invalid$photos/IMG_0001.JPG",
            "$photos/%2e%2e/IMG_0001.JPG",
            "/ccapi/ver140/deviceinformation",
        )
        for (path in unsafe) {
            val client = realClient()
            listing(root, listOf(path))

            assertTrue(path, runCatching { client.listMediaIdentities(10) }.isFailure)
            assertEquals(2, requests.size)
        }
    }

    @Test
    fun countTransportOrMalformedJsonErrorsAreNotHiddenByFallbackQueries() = runTest {
        for (fixture in listOf(Fixture("busy", 503), Fixture("not json"))) {
            val client = realClient()
            fixtures["$root?kind=number"] = fixture
            fixtures["$root?type=all,kind=number"] = Fixture("""{"pagenumber":0}""")
            fixtures[root] = Fixture(paths(emptyList()))

            assertTrue(runCatching { client.listMediaIdentities(10) }.isFailure)
            assertEquals(listOf("$root?kind=number"), requestedPaths())
        }
    }

    @Test
    fun failedLaterPageCannotReturnTheAlreadyObservedItemsAsACompleteBaseline() = runTest {
        val client = realClient()
        listing(root, listOf("$photos/IMG_0001.JPG"), emptyList())
        fixtures["$root?page=2&order=desc"] = Fixture("storage busy", 503)
        fixtures["$root?page=2"] = Fixture(paths(emptyList()))

        assertTrue(runCatching { client.listMediaIdentities(10) }.isFailure)
        assertEquals(3, requests.size)
        assertFalse(requestedPaths().contains("$root?page=2"))
    }

    @Test
    fun unsupportedCountQueriesCanUseTheExistingUnpagedListing() = runTest {
        val client = realClient()
        fixtures["$root?kind=number"] = Fixture("unsupported", 400)
        fixtures["$root?type=all,kind=number"] = Fixture("unsupported", 404)
        fixtures[root] = Fixture(paths(listOf("$photos/IMG_0001.JPG")))

        val inventory = client.listMediaIdentities(10)

        assertTrue(inventory.complete)
        assertEquals(1, inventory.items.size)
        assertEquals(listOf("$root?kind=number", "$root?type=all,kind=number", root), requestedPaths())
    }

    @Test
    fun unsupportedDescendingOrderUsesExistingReversePageTraversal() = runTest {
        val client = realClient()
        fixtures["$root?kind=number"] = Fixture("""{"pagenumber":2}""")
        fixtures["$root?page=1&order=desc"] = Fixture("unsupported", 400)
        fixtures["$root?page=1"] = Fixture(paths(listOf("$photos/IMG_0001.JPG")))
        fixtures["$root?page=2"] = Fixture(paths(listOf("$photos/IMG_0002.JPG", "$photos/IMG_0003.JPG")))

        val inventory = client.listMediaIdentities(10)

        assertTrue(inventory.complete)
        assertEquals(listOf("IMG_0003.JPG", "IMG_0002.JPG", "IMG_0001.JPG"), inventory.items.map { it.name })
        assertEquals(4, requests.size)
        assertIdentityRequestsOnly()
    }

    @Test
    fun oversizedContainerGraphIsIncompleteWithoutFollowingEveryFolder() = runTest {
        val client = realClient()
        listing(root, (1..600).map { "$root/card1/FOLDER_$it" })

        val inventory = client.listMediaIdentities(10)

        assertFalse(inventory.complete)
        assertTrue(inventory.items.isEmpty())
        assertEquals(2, requests.size)
    }

    @Test
    fun duplicateOnlyPaginationIsBoundedEvenWhenTheCameraAnnouncesBillionsOfPages() = runTest {
        val client = realClient()
        fixtures["$root?kind=number"] = Fixture("""{"pagenumber":2147483647}""")
        for (page in 1..511) {
            fixtures["$root?page=$page&order=desc"] = Fixture(
                paths(emptyList()),
                headers = mapOf("Connection" to "close"),
            )
        }

        val inventory = client.listMediaIdentities(10)

        assertFalse(inventory.complete)
        assertTrue(inventory.items.isEmpty())
        assertEquals(512, requests.size)
        assertFalse(requestedPaths().contains("$root?page=512&order=desc"))
        assertIdentityRequestsOnly()
    }

    @Test
    fun normalRecentListingStillHydratesAndOrdersMetadataAfterAnIdentityInventory() = runTest {
        val client = realClient()
        val first = "$photos/IMG_0001.JPG"
        val second = "$photos/IMG_0002.JPG"
        listing(root, listOf(first, second))
        fixtures["$first?kind=info"] = Fixture("""{"filesize":11,"lastmodifieddate":"2026-01-01T00:00:00Z"}""")
        fixtures["$second?kind=info"] = Fixture("""{"filesize":22,"lastmodifieddate":"2026-02-01T00:00:00Z"}""")

        val inventory = client.listMediaIdentities(3)
        assertTrue(inventory.complete)
        assertEquals(listOf(first, second), inventory.items.map { it.id })
        assertIdentityRequestsOnly()
        requests.clear()
        val batches = mutableListOf<List<CameraMediaItem>>()
        val recent = client.listMedia(2, batches::add)

        assertEquals(listOf(second, first), recent.map { it.id })
        assertEquals(listOf(22L, 11L), recent.map { it.sizeBytes })
        assertEquals(listOf(recent), batches)
        assertEquals(
            listOf("$root?kind=number", "$root?page=1&order=desc", "$first?kind=info", "$second?kind=info"),
            requestedPaths(),
        )
    }

    @Test
    fun realInventoryRequiresAdvertisedContentsGetAndPositiveLimit() = runTest {
        fixtures["/ccapi"] = Fixture("""{"ver140":[{"path":"/deviceinformation","get":true}]}""")
        val client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        client.initialize()
        requests.clear()

        assertTrue(runCatching { client.listMediaIdentities(10) }.isFailure)
        assertTrue(runCatching { client.listMediaIdentities(0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun simulatorInventoryUsesOnlyItsExistingEndpointAndHonorsExactCap() = runTest {
        val backend = CcapiCameraBackend(CameraConnection.CcapiNetwork(server.url("/").toString(), simulatorMode = true))
        backend.initialize()
        fixtures["/ccapi/media"] = Fixture("""{"items":[{"id":"one","name":"ONE.JPG","kind":"image"},{"id":"two","name":"TWO.JPG","kind":"image"}]}""")

        val complete = backend.listMediaIdentities(3)
        val capped = backend.listMediaIdentities(2)

        assertTrue(complete.complete)
        assertFalse(capped.complete)
        assertEquals(listOf("one", "two"), complete.items.map { it.id })
        assertEquals(listOf("/ccapi/media", "/ccapi/media"), requestedPaths())
    }

    @Test
    fun malformedSimulatorItemsCannotEstablishACompleteBaseline() = runTest {
        val client = CcapiClient(server.url("/").toString(), treatAsSimulator = true)
        client.initialize()
        val malformed = listOf(
            "{}", """{"items":[{"id":"","name":"ONE.JPG"}]}""",
            """{"items":[{"id":"one","name":""}]}""", """{"items":[{"id":1,"name":"ONE.JPG"}]}""",
        )
        for (body in malformed) {
            fixtures["/ccapi/media"] = Fixture(body)
            assertTrue(body, runCatching { client.listMediaIdentities(10) }.isFailure)
        }
    }

    @Test
    fun usbAndBridgeDoNotInheritInventorySupportFromMediaBrowsing() = runTest {
        val usb = UsbPtpCameraBackend(
            CameraConnection.AndroidUsbPtp("fixture-device"),
            PtpTransportFactory { error("Inventory must not open USB") },
        )
        val bridge = DesktopBridgeCameraBackend(CameraConnection.DesktopBridge(server.url("/").toString()))

        assertTrue(runCatching { usb.listMediaIdentities(10) }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching { bridge.listMediaIdentities(10) }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun conflictingSimulatorDuplicatesFailInsteadOfSilentlyChoosingAnIdentity() = runTest {
        val client = CcapiClient(server.url("/").toString(), treatAsSimulator = true)
        client.initialize()
        fixtures["/ccapi/media"] = Fixture("""{"items":[{"id":"one","name":"ONE.JPG"},{"id":"one","name":"TWO.JPG"}]}""")

        assertTrue(runCatching { client.listMediaIdentities(10) }.isFailure)
        assertEquals(listOf("/ccapi/media"), requestedPaths())
    }

    @Test
    fun singleAttemptDownloadUsesOnlyTheCanonicalOriginalAndChecksIntegrity() = runTest {
        val client = realClient()
        val path = "$photos/IMG_0001.JPG"
        val bytes = byteArrayOf(1, 2, 3, 4)
        fixtures[path] = binaryFixture(bytes)
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<CameraMediaTransferProgress>()

        val result = client.downloadMediaSingleAttempt(
            CameraMediaItem(server.url(path).toString() + "?kind=main", "IMG_0001.JPG", "image", sizeBytes = 4),
            output,
            progress::add,
        )

        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(4L, result.bytesTransferred)
        assertEquals(4L, progress.last().bytesTransferred)
        assertEquals(listOf(path), requestedPaths())
    }

    @Test
    fun singleAttemptDownloadNeverFollowsHttpErrorsIncludingImmediateRetryAfter() = runTest {
        for (status in listOf(404, 408, 503)) {
            val client = realClient()
            val path = "$photos/IMG_0001.JPG"
            fixtures[path] = Fixture("failed", status, headers = mapOf("Retry-After" to "0"))
            fixtures["$path?kind=main"] = binaryFixture(byteArrayOf(1, 2, 3, 4))
            val output = ByteArrayOutputStream()

            assertTrue(runCatching { client.downloadMediaSingleAttempt(mediaItem(path), output) }.isFailure)
            assertEquals(listOf(path), requestedPaths())
            assertEquals(0, output.size())
            assertFalse(CameraFeature.MEDIA_DOWNLOAD in client.observedFeatureSnapshot())
        }
    }

    @Test
    fun singleAttemptDownloadDoesNotFollowRedirects() = runTest {
        val client = realClient()
        val path = "$photos/IMG_0001.JPG"
        val redirected = "$photos/IMG_0002.JPG"
        fixtures[path] = Fixture(status = 302, headers = mapOf("Location" to server.url(redirected).toString()))
        fixtures[redirected] = binaryFixture(byteArrayOf(1, 2, 3, 4))

        assertTrue(runCatching { client.downloadMediaSingleAttempt(mediaItem(path), ByteArrayOutputStream()) }.isFailure)
        assertEquals(listOf(path), requestedPaths())
    }

    @Test
    fun singleAttemptDownloadDoesNotRetryConnectionLossBeforeHeaders() = runTest {
        val client = realClient()
        val path = "$photos/IMG_0001.JPG"
        fixtures[path] = Fixture(socketPolicy = SocketPolicy.DISCONNECT_AFTER_REQUEST)
        fixtures["$path?kind=main"] = binaryFixture(byteArrayOf(1, 2, 3, 4))

        assertTrue(runCatching { client.downloadMediaSingleAttempt(mediaItem(path), ByteArrayOutputStream()) }.isFailure)
        assertEquals(listOf(path), requestedPaths())
    }

    @Test
    fun singleAttemptDownloadMakesOnlyOneConnectionWhenPeerClosesBeforeRequest() = runTest {
        val connections = AtomicInteger()
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                connections.incrementAndGet()
            }
        }).build()
        val client = CcapiClient(server.url("/").toString(), httpClient = http, treatAsSimulator = true)
        client.initialize()
        server.dispatcher = QueueDispatcher().apply {
            enqueueResponse(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            enqueueResponse(MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3, 4))))
        }

        assertTrue(runCatching {
            client.downloadMediaSingleAttempt(CameraMediaItem("one", "ONE.JPG", "image", sizeBytes = 4), ByteArrayOutputStream())
        }.isFailure)
        assertEquals(1, connections.get())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun singleAttemptDownloadDoesNotRetryPartialResponseBodies() = runTest {
        val client = realClient()
        val path = "$photos/IMG_0001.JPG"
        val bytes = ByteArray(256 * 1024) { 1 }
        fixtures[path] = binaryFixture(bytes).copy(socketPolicy = SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        fixtures["$path?kind=main"] = binaryFixture(bytes)
        val output = ByteArrayOutputStream()

        assertTrue(runCatching {
            client.downloadMediaSingleAttempt(mediaItem(path).copy(sizeBytes = bytes.size.toLong()), output)
        }.isFailure)
        assertTrue(output.size() > 0)
        assertTrue(output.size() < bytes.size)
        assertEquals(listOf(path), requestedPaths())
    }

    @Test
    fun singleAttemptDownloadRejectsTextWithoutTryingOriginalVariants() = runTest {
        val client = realClient()
        val path = "$photos/IMG_0001.JPG"
        fixtures[path] = Fixture("""{"kind":"metadata"}""")
        fixtures["$path?kind=main"] = binaryFixture(byteArrayOf(1, 2, 3, 4))
        val output = ByteArrayOutputStream()

        assertTrue(runCatching { client.downloadMediaSingleAttempt(mediaItem(path), output) }.isFailure)
        assertEquals(listOf(path), requestedPaths())
        assertEquals(0, output.size())
    }

    @Test
    fun normalManualDownloadStillFallsBackAfterTextResponse() = runTest {
        val client = realClient()
        val path = "$photos/IMG_0001.JPG"
        fixtures[path] = Fixture("""{"kind":"metadata"}""")
        val bytes = byteArrayOf(1, 2, 3, 4)
        fixtures["$path?kind=main"] = binaryFixture(bytes)
        val output = ByteArrayOutputStream()

        val result = client.downloadMedia(mediaItem(path), output)

        assertEquals(4L, result.bytesTransferred)
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(listOf(path, "$path?kind=main"), requestedPaths())
    }

    @Test
    fun usbAndBridgeAlsoFailClosedForSingleAttemptDownload() = runTest {
        val usb = UsbPtpCameraBackend(
            CameraConnection.AndroidUsbPtp("fixture-device"),
            PtpTransportFactory { error("Download must not open USB") },
        )
        val bridge = DesktopBridgeCameraBackend(CameraConnection.DesktopBridge(server.url("/").toString()))
        val item = CameraMediaItem("one", "ONE.JPG", "image", sizeBytes = 4)

        assertTrue(runCatching { usb.downloadMediaSingleAttempt(item, ByteArrayOutputStream()) }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching { bridge.downloadMediaSingleAttempt(item, ByteArrayOutputStream()) }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(requests.isEmpty())
    }

    private fun binaryFixture(bytes: ByteArray) = Fixture(bytes = bytes, headers = mapOf("Content-Type" to "image/jpeg"))

    private fun mediaItem(path: String) = CameraMediaItem(path, path.substringAfterLast('/'), "image", sizeBytes = 4)

    private suspend fun realClient(): CcapiClient {
        fixtures.clear()
        fixtures["/ccapi"] = Fixture("""{"ver140":[{"path":"/contents","get":true}]}""")
        val client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        client.initialize()
        assertTrue(client.isRealCamera)
        requests.clear()
        return client
    }

    private fun listing(container: String, vararg pages: List<String>) {
        fixtures["$container?kind=number"] = Fixture("""{"pagenumber":${pages.size}}""")
        pages.forEachIndexed { index, page ->
            fixtures["$container?page=${index + 1}&order=desc"] = Fixture(paths(page))
        }
    }

    private fun paths(items: List<String>): String = JSONObject().put("path", JSONArray(items)).toString()

    private fun requestedPaths(): List<String> = requests.map { requireNotNull(it.path) }

    private fun assertIdentityRequestsOnly() {
        assertTrue(requests.all { it.method == "GET" })
        assertFalse(requestedPaths().any { "kind=info" in it || "kind=main" in it })
    }
}
