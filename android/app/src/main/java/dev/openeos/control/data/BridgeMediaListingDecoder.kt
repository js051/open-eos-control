package dev.openeos.control.data

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONObject

/** Browsing snapshots only; completion never supplies strict inventory evidence. */
internal class BridgeMediaListingDecoder(private val sessionId: String, private val limit: Int?) {
    companion object {
        const val MEDIA_TYPE = "application/vnd.open-eos.media-list.v1+ndjson"
        const val MAX_LINE_BYTES = 65_536
        const val MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024
        const val MAX_ITEMS = 100_000
    }
    private val line = ByteArrayOutputStream()
    private var requestId: String? = null
    private var nextSequence = 0L
    private var revision = 0L
    private var pendingTotal: Int? = null
    private var pending = ArrayList<CameraMediaItem>()
    private val identities = HashSet<String>()
    private var snapshotBytes = 0
    private var latest: List<CameraMediaItem> = emptyList()
    private var complete = false
    private var terminalError: String? = null

    fun consume(byte: Byte, parseItem: (JSONObject) -> CameraMediaItem?): List<CameraMediaItem>? {
        check(!complete) { "Data followed the listing terminal record." }
        check(line.size() < MAX_LINE_BYTES) { "Listing record exceeded its byte limit." }
        line.write(byte.toInt())
        if (byte.toInt() != 10) return null
        val recordBytes = line.size()
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(line.toByteArray())).toString()
        line.reset()
        StrictMediaJson(text).validate()
        val record = JSONObject(text)
        val type = record.opt("type") as? String
        val id = record.opt("requestId") as? String
        check(id != null && id.isNotEmpty() && id.toByteArray(Charsets.UTF_8).size <= 256 &&
            record.opt("sessionId") == sessionId && record.opt("scope") == (if (limit == null) "full" else "recent")) {
            "Listing record binding changed."
        }
        check(integer(record, "seq") == nextSequence) { "Listing sequence changed." }
        nextSequence++
        if (requestId == null) {
            check(type == "start" && nextSequence == 1L && if (limit == null) {
                record.has("limit") && record.opt("limit") === JSONObject.NULL
            } else integer(record, "limit") == limit.toLong()) { "Invalid listing start record." }
            requestId = id
            return null
        }
        check(id == requestId) { "Listing request changed." }
        when (type) {
            "snapshot" -> {
                val total = integer(record, "total")
                val offset = integer(record, "offset")
                val items = record.opt("items") as? JSONArray
                val last = record.opt("last") as? Boolean
                check(integer(record, "revision") == revision + 1 && offset == pending.size.toLong() &&
                    total != null && total <= MAX_ITEMS && (limit == null || total <= limit) &&
                    (pendingTotal == null || pendingTotal?.toLong() == total) &&
                    items != null && items.length() <= 128 && items.length() <= total - pending.size &&
                    last != null && last == (pending.size + items.length() == total.toInt()) &&
                    (items.length() > 0 || (last && total == 0L)) &&
                    recordBytes <= MAX_SNAPSHOT_BYTES - snapshotBytes) {
                    "Listing snapshot is malformed or exceeds its resource limit."
                }
                snapshotBytes += recordBytes
                pendingTotal = total.toInt()
                for (index in 0 until items.length()) {
                    val value = items.opt(index) as? JSONObject ?: error("Invalid listing item.")
                    val itemId = value.opt("id") as? String
                    val name = value.opt("name") as? String
                    check(itemId != null && itemId.isNotBlank() && name != null && name.isNotBlank()) { "Invalid listing identity." }
                    val item = parseItem(value) ?: error("Invalid listing item.")
                    check(item.id == itemId && identities.add(itemId)) { "Duplicate or changed listing identity." }
                    pending.add(item)
                }
                if (!last) return null
                revision++
                latest = pending.toList()
                pending = ArrayList()
                identities.clear()
                pendingTotal = null
                snapshotBytes = 0
                return latest
            }
            "complete" -> {
                check(revision > 0 && pendingTotal == null && integer(record, "revision") == revision &&
                    integer(record, "count") == latest.size.toLong() && record.opt("scopeComplete") == true) {
                    "Invalid listing completion."
                }
                complete = true
                return null
            }
            "error" -> {
                val code = record.opt("code") as? String
                val message = record.opt("message") as? String
                check(!code.isNullOrEmpty() && !message.isNullOrEmpty()) { "Invalid listing error record." }
                complete = true
                terminalError = "Desktop Bridge listing failed [$code]: $message"
                return null
            }
            else -> error("Unexpected listing record.")
        }
    }

    fun finish(): List<CameraMediaItem> {
        check(complete && line.size() == 0) { "Listing ended without a complete terminal record." }
        terminalError?.let { error(it) }
        check(pendingTotal == null) { "Listing snapshot is incomplete." }
        return latest
    }

    private fun integer(record: JSONObject, key: String): Long? = when (val value = record.opt(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }?.takeIf { it in 0L..9_007_199_254_740_991L }
}
