package dev.openeos.control.data

import java.io.InputStream

/** Empty HTTP 204 or a complete bounded JSON response; no headers-only command evidence. */
internal fun readShootingSettingResponseText(input: InputStream, declaredBytes: Long): String {
    val text = readStrictMediaJsonText(input, declaredBytes, 65_536)
    check(declaredBytes < 0 || text.toByteArray(Charsets.UTF_8).size.toLong() == declaredBytes)
    if (text.isNotEmpty()) StrictMediaJson(text).validate()
    return text
}

internal fun requireShootingSettingHttpResponse(status: Int, contentRanges: List<String>,
    lengths: List<String>, encodings: List<String>) {
    check(status in setOf(200, 201, 202, 204) && contentRanges.isEmpty())
    check(lengths.size <= 1 && (lengths.isEmpty() || encodings.isEmpty()))
    check(lengths.all { Regex("^[0-9]+$").matches(it) && it.toLongOrNull()?.let { value -> value >= 0L } == true })
    check(encodings.isEmpty() || (encodings.size == 1 && encodings.single().equals("chunked", true)))
}
