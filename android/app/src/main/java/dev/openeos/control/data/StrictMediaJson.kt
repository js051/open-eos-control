package dev.openeos.control.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal const val MAX_MEDIA_METADATA_JSON_BYTES = 65_536

/** Strict UTF-8 and a finite byte budget before grammar validation or platform JSON decoding. */
internal fun readStrictMediaJsonText(input: InputStream, declaredBytes: Long, maximumBytes: Int): String {
    require(maximumBytes > 0)
    check(declaredBytes <= maximumBytes) { "Media JSON exceeds its byte limit." }
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        check(count <= maximumBytes - output.size()) { "Media JSON exceeds its byte limit." }
        output.write(buffer, 0, count)
    }
    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
}

/** Android's JSONTokener accepts non-JSON syntax. Validate grammar and decoded keys first. */
internal class StrictMediaJson(private val text: String) {
    private var offset = 0
    fun validate() {
        value(0)
        whitespace()
        check(offset == text.length) { "Trailing listing JSON." }
    }
    private fun whitespace() { while (offset < text.length && text[offset] in " \t\r\n") offset++ }
    private fun take(char: Char): Boolean {
        whitespace()
        return if (offset < text.length && text[offset] == char) { offset++; true } else false
    }
    private fun value(depth: Int) {
        check(depth <= 32) { "Listing JSON exceeds its nesting limit." }
        whitespace()
        check(offset < text.length) { "Truncated listing JSON." }
        when (text[offset]) {
            '{' -> {
                offset++
                val keys = HashSet<String>()
                if (take('}')) return
                do {
                    whitespace()
                    check(keys.add(string())) { "Duplicate listing JSON member." }
                    check(take(':'))
                    value(depth + 1)
                } while (take(','))
                check(take('}'))
            }
            '[' -> {
                offset++
                if (take(']')) return
                do { value(depth + 1) } while (take(','))
                check(take(']'))
            }
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> {
                val start = offset
                while (offset < text.length && text[offset] in "-+0123456789.eE") offset++
                check(NUMBER.matches(text.substring(start, offset))) { "Invalid listing JSON number." }
            }
        }
    }
    private fun literal(value: String) { check(text.startsWith(value, offset)); offset += value.length }
    private fun string(): String {
        check(offset < text.length && text[offset++] == '"')
        val result = StringBuilder()
        while (offset < text.length) {
            val char = text[offset++]
            if (char == '"') {
                var index = 0
                while (index < result.length) {
                    val unit = result[index++]
                    if (unit.isHighSurrogate()) {
                        check(index < result.length && result[index++].isLowSurrogate())
                    } else check(!unit.isLowSurrogate())
                }
                return result.toString()
            }
            check(char >= ' ')
            if (char != '\\') { result.append(char); continue }
            check(offset < text.length)
            when (val escaped = text[offset++]) {
                '"', '\\', '/' -> result.append(escaped)
                'b' -> result.append('\b')
                'f' -> result.append('\u000c')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                    check(offset + 4 <= text.length)
                    val hex = text.substring(offset, offset + 4)
                    check(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                    result.append(hex.toInt(16).toChar())
                    offset += 4
                }
                else -> error("Invalid listing JSON escape.")
            }
        }
        error("Truncated listing JSON string.")
    }
    companion object { private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?") }
}
