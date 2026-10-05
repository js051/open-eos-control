package dev.openeos.control.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CameraMediaRatingJsonTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun simulatorListingKeepsUnknownSeparateFromEveryValidRatingWithoutMetadataReads() = runTest {
        server.enqueue(jsonResponse(listingJson()))
        val client = CcapiClient(server.url("/").toString(), treatAsSimulator = true)

        val items = client.listMedia()

        assertEquals(ratingCases.map { it.expected }, items.map { it.rating })
        assertEquals(ratingCases.map { it.id }, items.map { it.id })
        assertRequest("GET", "/ccapi/media")
        assertEquals("Rating parsing must not fetch per-item metadata", 1, server.requestCount)
    }

    @Test fun bridgeListingKeepsUnknownSeparateFromEveryValidRatingWithoutMetadataReads() = runTest {
        val client = bridgeClient()
        server.enqueue(jsonResponse(listingJson()))

        val items = client.listMedia()

        assertEquals(ratingCases.map { it.expected }, items.map { it.rating })
        assertEquals(ratingCases.map { it.id }, items.map { it.id })
        assertRequest("GET", "/v1/session/rating-session/media")
        assertEquals("Only session setup and the listing are needed", 3, server.requestCount)
    }

    @Test fun bridgeInfoReplacesOldRatingWithAuthoritativeUnknownOrExactInteger() = runTest {
        val client = bridgeClient()
        ratingCases.forEach { case ->
            server.enqueue(jsonResponse(case.itemJson()))

            val updated = client.mediaInfo(CameraMediaItem(case.id, "IMG.JPG", "image", rating = 4))

            assertEquals(case.id, case.expected, updated.rating)
            assertRequest("GET", "/v1/session/rating-session/media/${case.id}/info")
        }
        assertEquals(2 + ratingCases.size, server.requestCount)
    }

    @Test fun bridgeRatingWriteAcceptsMatchingIntegerOrIntegerStringReadback() = runTest {
        val client = bridgeClient()
        val validCases = ratingCases.filter { it.expected != null }
        validCases.forEach { case ->
            server.enqueue(jsonResponse(case.itemJson()))
            val requestedRating = requireNotNull(case.expected)

            val updated = client.setMediaRating(CameraMediaItem(case.id, "IMG.JPG", "image", rating = 4), requestedRating)

            assertEquals(case.id, case.expected, updated.rating)
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/v1/session/rating-session/media/${case.id}/rating", request.path)
            assertEquals(requestedRating, JSONObject(request.body.readUtf8()).getInt("value"))
            assertTrue(CameraFeature.MEDIA_RATING in client.observedFeatureSnapshot())
        }
        assertEquals("The Bridge response confirms the write without another read", 2 + validCases.size, server.requestCount)
    }

    @Test fun bridgeRatingWriteRejectsUnknownOrMalformedReadbackWithoutRetryOrObservedEvidence() = runTest {
        val client = bridgeClient()
        val unknownCases = ratingCases.filter { it.expected == null }
        unknownCases.forEach { case ->
            server.enqueue(jsonResponse(case.itemJson()))

            val failure = runCatching {
                client.setMediaRating(CameraMediaItem(case.id, "IMG.JPG", "image", rating = 4), 5)
            }.exceptionOrNull()

            assertRatingNotConfirmed(case.id, failure)
            assertFalse(CameraFeature.MEDIA_RATING in client.observedFeatureSnapshot())
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/v1/session/rating-session/media/${case.id}/rating", request.path)
            assertEquals(5, JSONObject(request.body.readUtf8()).getInt("value"))
        }
        assertEquals("An unknown outcome must not cause another write or read", 2 + unknownCases.size, server.requestCount)
    }

    @Test fun bridgeRatingWriteRejectsDifferentValidRatingWithoutRetryOrObservedEvidence() = runTest {
        val client = bridgeClient()
        var writeCount = 0
        (0..5).forEach { requestedRating ->
            (0..5).filter { it != requestedRating }.forEach { returnedRating ->
                val case = RatingCase("rating-$requestedRating-$returnedRating", returnedRating.toString(), returnedRating)
                server.enqueue(jsonResponse(case.itemJson()))

                val failure = runCatching {
                    client.setMediaRating(CameraMediaItem(case.id, "IMG.JPG", "image"), requestedRating)
                }.exceptionOrNull()

                assertRatingNotConfirmed(case.id, failure)
                assertFalse(CameraFeature.MEDIA_RATING in client.observedFeatureSnapshot())
                val request = server.takeRequest()
                assertEquals("PUT", request.method)
                assertEquals("/v1/session/rating-session/media/${case.id}/rating", request.path)
                assertEquals(requestedRating, JSONObject(request.body.readUtf8()).getInt("value"))
                writeCount += 1
            }
        }
        assertEquals("A mismatched outcome must not cause another write or read", 2 + writeCount, server.requestCount)
    }

    @Test fun bridgeInfoRejectsMetadataForAnotherItemWithoutAdditionalReads() = runTest {
        val client = bridgeClient()
        server.enqueue(jsonResponse(RatingCase("another-item", "5", 5).itemJson()))

        val failure = runCatching {
            client.mediaInfo(CameraMediaItem("requested-item", "IMG.JPG", "image", rating = 2))
        }.exceptionOrNull()

        assertTrue("The response must belong to the requested item", failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("does not belong to the requested item"))
        assertRequest("GET", "/v1/session/rating-session/media/requested-item/info")
        assertEquals(3, server.requestCount)
    }

    @Test fun bridgeRatingRejectsResponseForAnotherItemBeforeRecordingEvidenceOrRetrying() = runTest {
        val client = bridgeClient()
        server.enqueue(jsonResponse(RatingCase("another-item", "5", 5).itemJson()))

        val failure = runCatching {
            client.setMediaRating(CameraMediaItem("requested-item", "IMG.JPG", "image", rating = 2), 5)
        }.exceptionOrNull()

        assertTrue("The response must belong to the requested item", failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("does not belong to the requested item"))
        assertFalse(CameraFeature.MEDIA_RATING in client.observedFeatureSnapshot())
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/v1/session/rating-session/media/requested-item/rating", request.path)
        assertEquals(5, JSONObject(request.body.readUtf8()).getInt("value"))
        assertEquals("An untrusted response must not cause another write or a follow-up read", 3, server.requestCount)
    }

    @Test fun canonicalInfoKeepsOffAndOneThroughFiveContract() = runTest {
        val client = canonicalClient()
        val item = CameraMediaItem(CANON_PATH, "IMG.JPG", "image", rating = 4)
        val cases = listOf(
            RatingCase("missing", null, null),
            RatingCase("null", "null", null),
            RatingCase("off", "\"off\"", 0),
            RatingCase("numeric-zero", "0", null),
            RatingCase("string-zero", "\"0\"", null),
            RatingCase("invalid", "\"bad\"", null),
            RatingCase("fraction", "3.7", null),
        ) + (1..5).map { RatingCase("star-$it", "\"$it\"", it) }
        cases.forEach { case ->
            server.enqueue(jsonResponse(case.ratingField()?.let { "{$it}" } ?: "{}"))

            assertEquals(case.id, case.expected, client.mediaInfo(item).rating)
            assertRequest("GET", "$CANON_PATH?kind=info")
        }
        assertEquals(1 + cases.size, server.requestCount)
    }

    @Test fun canonicalRatingWritesStillRequireOffOrStarsReadback() = runTest {
        val client = canonicalClient()
        val item = CameraMediaItem(CANON_PATH, "IMG.JPG", "image")
        listOf(0, 5).forEach { rating ->
            val wireValue = if (rating == 0) "off" else rating.toString()
            server.enqueue(jsonResponse("{}"))
            server.enqueue(jsonResponse("""{"rating":"$wireValue"}"""))

            assertEquals(rating, client.setMediaRating(item, rating).rating)
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals(CANON_PATH, request.path)
            val payload = JSONObject(request.body.readUtf8())
            assertEquals("rating", payload.getString("action"))
            assertEquals(wireValue, payload.getString("value"))
            assertRequest("GET", "$CANON_PATH?kind=info")
        }
        assertEquals(5, server.requestCount)
    }

    private suspend fun bridgeClient(): DesktopBridgeClient {
        server.enqueue(jsonResponse("""{"service":"open-eos-control-bridge"}"""))
        server.enqueue(jsonResponse("""{"id":"rating-session"}"""))
        return DesktopBridgeClient(server.url("/").toString()).also {
            it.initialize()
            assertRequest("GET", "/health")
            assertRequest("POST", "/v1/session")
        }
    }

    private suspend fun canonicalClient(): CcapiClient {
        server.enqueue(jsonResponse("""{"ver110":[{"path":"/contents","get":true,"put":true}]}"""))
        return CcapiClient(server.url("/").toString(), treatAsSimulator = false).also {
            it.initialize()
            assertRequest("GET", "/ccapi")
        }
    }

    private fun assertRequest(method: String, path: String) {
        val request = server.takeRequest()
        assertEquals(method, request.method)
        assertEquals(path, request.path)
    }

    private fun assertRatingNotConfirmed(label: String, failure: Throwable?) {
        assertTrue("$label must not be a successful rating write", failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("did not confirm the requested rating"))
    }

    private fun listingJson(): String = """{"items":[${ratingCases.joinToString(",") { it.itemJson() }}]}"""

    private fun jsonResponse(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private data class RatingCase(val id: String, val literal: String?, val expected: Int?) {
        fun ratingField(): String? = literal?.let { "\"rating\":$it" }
        fun itemJson(): String = """{"id":"$id","name":"IMG.JPG","kind":"image"${ratingField()?.let { ",$it" }.orEmpty()}}"""
    }

    private companion object {
        const val CANON_PATH = "/ccapi/ver110/contents/card1/100CANON/IMG.JPG"
        val ratingCases = listOf(
            RatingCase("missing", null, null),
            RatingCase("null", "null", null),
            RatingCase("bad-string", "\"bad\"", null),
            RatingCase("false", "false", null),
            RatingCase("true", "true", null),
            RatingCase("object", "{}", null),
            RatingCase("array", "[]", null),
            RatingCase("off-is-not-normalized", "\"off\"", null),
            RatingCase("fraction", "3.7", null),
            RatingCase("string-fraction", "\"3.7\"", null),
            RatingCase("whole-decimal", "3.0", null),
            RatingCase("whole-exponent", "3e0", null),
            RatingCase("string-decimal", "\"3.0\"", null),
            RatingCase("string-exponent", "\"3e0\"", null),
            RatingCase("precise-fraction", "3.0000000000000000001", null),
            RatingCase("overflow", "4294967296", null),
            RatingCase("string-overflow", "\"4294967296\"", null),
            RatingCase("large-integer", "18446744073709551616", null),
            RatingCase("negative", "-1", null),
            RatingCase("too-many-stars", "6", null),
        ) + (0..5).flatMap { rating ->
            listOf(
                RatingCase("integer-$rating", rating.toString(), rating),
                RatingCase("string-$rating", "\"$rating\"", rating),
            )
        }
    }
}
