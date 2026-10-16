package dev.openeos.control.data

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class StrictMediaJsonTest {
    @Test fun rejectsPlatformLenientSyntaxAndDuplicateDecodedKeysBeforeJsonTokener() {
        val invalid = listOf(
            "{rating:3}", "{'rating':3}", "{\"rating\":03}", "{\"rating\":0x3}", "{\"rating\":+3}",
            "{\"rating\":3,}", "{\"rating\":/*comment*/3}", "{\"rating\":3}//comment", "{\"rating\":NaN}",
            "{\"rating\":0,\"rating\":3}", "{\"rating\":0,\"rati\\u006eg\":3}", "{\"rating\":3};{}",
            "{\"rating\":3}{}", "{\"id\":\"\\ud800\"}", "{\"id\":\"\\udc00\"}", "[1,,2]",
            "{\"x\":{\"rating\":0,\"rating\":3}}", "[".repeat(34) + "0" + "]".repeat(34),
        )
        invalid.forEach { assertNotNull(it, runCatching { StrictMediaJson(it).validate() }.exceptionOrNull()) }
    }
    @Test fun acceptsStandardGrammarWithoutCoercingNumberOrStringEvidence() {
        listOf("{}", "{\"rating\":3}", "{\"rating\":3.0}", "{\"rating\":3e0}", "{\"rating\":3.0000000000000000001}",
            "{\"id\":\"é\",\"rating\":\"3\",\"array\":[true,false,null,-10.25e+2]}", " {\"id\":\"\\ud83d\\ude00\"}\r\n")
            .forEach { StrictMediaJson(it).validate() }
    }
    @Test fun strictUtf8AndByteLimitPrecedeGrammarAndPlatformDecode() {
        val valid = "{\"id\":\"é\"}".toByteArray()
        assertEquals(String(valid, Charsets.UTF_8), readStrictMediaJsonText(ByteArrayInputStream(valid), valid.size.toLong(), valid.size))
        assertNotNull(runCatching { readStrictMediaJsonText(ByteArrayInputStream(valid), -1, valid.size - 1) }.exceptionOrNull())
        assertNotNull(runCatching { readStrictMediaJsonText(ByteArrayInputStream(valid), valid.size.toLong(), valid.size - 1) }.exceptionOrNull())
        assertNotNull(runCatching { readStrictMediaJsonText(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)), 2, 10) }.exceptionOrNull())
    }
}
