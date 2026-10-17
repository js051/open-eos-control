package dev.openeos.control.ui

import org.junit.Assert.*
import org.junit.Test

class LiveViewDecodeFailureTest {
    @Test fun byteCountAndArbitraryContentTypeRoundTripExactly() {
        for (bytes in listOf(0, 4, Int.MAX_VALUE)) {
            for (type in listOf(null, "", "image/jpeg", "繁中/📷;%s:%1\$d[]\n\r\t\u0000",
                "SocketTimeoutException", "\ud800", "\udfff")) {
                val failure = LiveViewDecodeFailure(bytes, type)
                assertEquals(failure, liveViewDecodeFailure(failure.messageToken))
            }
        }
    }

    @Test fun nullAndEmptyHaveDistinctCanonicalRepresentations() {
        assertEquals("[OpenEOS.LiveViewDecode:v1:0:n]", LiveViewDecodeFailure(0, null).messageToken)
        assertEquals("[OpenEOS.LiveViewDecode:v1:0:s]", LiveViewDecodeFailure(0, "").messageToken)
        assertEquals("[OpenEOS.LiveViewDecode:v1:4:s0061]", LiveViewDecodeFailure(4, "a").messageToken)
    }

    @Test fun noncanonicalMalformedAndOutOfRangeTokensAreRejected() {
        for (payload in listOf("-1:n", "+1:n", "01:n", "-0:n", "2147483648:n", ":n",
            "1:", "1:N", "1:nextra", "1:s0", "1:s004A", "1:szzzz", "1:s0000:n")) {
            assertNull(payload, liveViewDecodeFailure("[OpenEOS.LiveViewDecode:v1:$payload]"))
        }
        val token = LiveViewDecodeFailure(4, null).messageToken
        for (message in listOf(" $token", "$token\n", "$token]", token.dropLast(1),
            token.replace("v1", "v2"), token.lowercase())) {
            assertNull(message, liveViewDecodeFailure(message))
        }
    }

    @Test fun rawProviderMessagesAndWrappedTokensAreNotAppFailures() {
        val token = LiveViewDecodeFailure(4, "image/jpeg").messageToken
        for (message in listOf("IOException: $token", "provider says $token", "$token extra",
            "IllegalStateException: Live view frame was received but Android could not decode it (4 bytes, image/jpeg).")) {
            assertNull(message, liveViewDecodeFailure(message))
        }
    }

    @Test fun typedExceptionKeepsOriginalDetailsAndDoesNotExposeAnImplicitTokenInItsMessage() {
        val error = LiveViewFrameDecodeException(4, "image/jpeg")
        assertEquals(LiveViewDecodeFailure(4, "image/jpeg"), error.failure)
        assertNull(liveViewDecodeFailure(error.message.orEmpty()))
        assertTrue(error is IllegalStateException)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeByteCountCannotBeEmitted() { LiveViewFrameDecodeException(-1, null) }
}
