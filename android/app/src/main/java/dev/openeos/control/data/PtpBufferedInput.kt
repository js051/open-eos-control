package dev.openeos.control.data

internal class PtpBufferedInput(
    chunkBytes: Int,
    private val readChunk: suspend (ByteArray) -> Int,
) {
    private val buffer = ByteArray(chunkBytes.coerceAtLeast(1))
    private var offset = 0
    private var limit = 0

    suspend fun readExact(byteCount: Int): ByteArray {
        require(byteCount >= 0) { "Byte count cannot be negative." }
        if (byteCount == 0) return byteArrayOf()
        val result = ByteArray(byteCount)
        var resultOffset = 0
        while (resultOffset < result.size) {
            resultOffset += readInto(result, resultOffset, result.size - resultOffset)
        }
        return result
    }

    suspend fun readInto(destination: ByteArray, destinationOffset: Int, requested: Int): Int {
        require(destinationOffset >= 0 && requested > 0 && destinationOffset <= destination.size - requested) {
            "Invalid destination range offset=$destinationOffset, count=$requested, size=${destination.size}."
        }
        if (offset >= limit) refill()
        val count = minOf(requested, limit - offset)
        buffer.copyInto(
            destination,
            destinationOffset = destinationOffset,
            startIndex = offset,
            endIndex = offset + count,
        )
        offset += count
        return count
    }

    private suspend fun refill() {
        val count = readChunk(buffer)
        if (count !in 1..buffer.size) {
            throw PtpProtocolException(
                "PTP bulk reader returned $count bytes for a ${buffer.size}-byte input buffer."
            )
        }
        offset = 0
        limit = count
    }
}
