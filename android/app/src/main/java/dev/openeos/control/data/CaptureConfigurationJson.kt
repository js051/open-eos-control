package dev.openeos.control.data

import java.io.InputStream

/** Capture-only ACK/recovery budget; malformed UTF-8, framing, duplicate keys and trailing JSON fail closed. */
internal fun readCaptureConfigurationJsonText(input: InputStream, declaredBytes: Long): String {
    val text = readStrictMediaJsonText(input, declaredBytes, 65_536)
    check(declaredBytes < 0 || text.toByteArray(Charsets.UTF_8).size.toLong() == declaredBytes) {
        "Incomplete capture configuration response body."
    }
    StrictMediaJson(text).validate()
    return text
}
