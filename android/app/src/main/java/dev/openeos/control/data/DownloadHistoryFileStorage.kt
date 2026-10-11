package dev.openeos.control.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal const val DOWNLOAD_HISTORY_SNAPSHOT_NAME = "history.json"
internal const val MAX_DOWNLOAD_HISTORY_SNAPSHOT_BYTES = 256 * 1024

internal enum class DownloadHistoryWritePhase { BEFORE_TEMP_WRITE, BEFORE_SYNC, BEFORE_MOVE, AFTER_MOVE }

internal enum class DownloadHistoryInstallation { NOT_INSTALLED, INSTALLED, UNKNOWN }

/** No path or underlying exception text escapes storage. UNKNOWN includes a throwing rename. */
internal class DownloadHistoryWriteException(val installation: DownloadHistoryInstallation) : IOException("History write failed")

internal data class DownloadHistorySnapshot(
    val entries: List<DownloadHistoryEntry> = emptyList(),
    val worklists: List<DownloadWorklist> = emptyList(),
)

/** Real-file storage; the callback only injects faults around actual writes in JVM tests. */
internal class DownloadHistoryFileStorage private constructor(
    private val directory: File,
    private val beforeWritePhase: (DownloadHistoryWritePhase) -> Unit,
    private val atomicMove: (java.nio.file.Path, java.nio.file.Path) -> Unit,
) {
    constructor(directory: File, beforeWritePhase: (DownloadHistoryWritePhase) -> Unit = {}) :
        this(directory, beforeWritePhase, { source, destination ->
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Unit
        })

    companion object {
        internal fun withAtomicMoveForTest(
            directory: File,
            move: (java.nio.file.Path, java.nio.file.Path) -> Unit,
        ) = DownloadHistoryFileStorage(directory, {}, move)
    }

    private val snapshot get() = File(directory, DOWNLOAD_HISTORY_SNAPSHOT_NAME)

    /** Compatibility view of legacy, actual-start-only records. */
    fun read(): List<DownloadHistoryEntry> = readSnapshot().entries

    fun readSnapshot(): DownloadHistorySnapshot {
        if (Files.notExists(snapshot.toPath())) return DownloadHistorySnapshot()
        val bytes = snapshot.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_DOWNLOAD_HISTORY_SNAPSHOT_BYTES + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
                require(output.size() <= MAX_DOWNLOAD_HISTORY_SNAPSHOT_BYTES)
            }
            output.toByteArray()
        }
        val text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        // Android JSONTokener also accepts comments, duplicate keys and non-JSON syntax.
        // Validate first so malformed old or new snapshots remain untouched, never coerced.
        StrictHistoryJson(text).validate()
        val tokener = JSONTokener(text)
        val root = tokener.nextValue() as? JSONObject ?: throw IOException("Invalid history snapshot")
        require(tokener.nextClean() == '\u0000')
        val schema = root.exactLong("schema")
        require(schema in 1L..3L)
        require(root.keySetCompat() == setOf("schema", "records") + if (schema == 3L) setOf("worklists") else emptySet())
        val records = root.get("records") as? JSONArray ?: throw IOException("Invalid history records")
        require(records.length() <= MAX_DOWNLOAD_HISTORY_ENTRIES)
        val ids = HashSet<String>()
        fun id(value: String): String {
            require(UUID.fromString(value).toString() == value && ids.add(value))
            return value
        }
        fun entry(record: JSONObject): DownloadHistoryEntry {
            val fields = setOf("receiptId", "filename", "destination", "startedAtMillis", "finishedAtMillis", "outcome") +
                if (schema >= 2L) setOf("cleanupUnconfirmed") else emptySet()
            require(record.keySetCompat() == fields)
            val receiptId = id(record.exactString("receiptId"))
            val filename = record.exactString("filename").also { require(it == sanitizeDownloadHistoryFilename(it)) }
            val destination = DownloadHistoryDestination.valueOf(record.exactString("destination"))
            val outcome = DownloadHistoryOutcome.valueOf(record.exactString("outcome"))
            val cleanupUnconfirmed = if (schema >= 2L) record.get("cleanupUnconfirmed") as? Boolean
                ?: throw IOException("Invalid history cleanup state") else false
            require(!cleanupUnconfirmed || outcome == DownloadHistoryOutcome.FAILED || outcome == DownloadHistoryOutcome.CANCELLED)
            val started = record.exactLong("startedAtMillis").also { require(it >= 0L) }
            val finished = if (record.get("finishedAtMillis") === JSONObject.NULL) null
                else record.exactLong("finishedAtMillis").also { require(it >= 0L) }
            require((finished != null) == outcome.isTerminal())
            return DownloadHistoryEntry(receiptId, filename, destination, started, finished, outcome, cleanupUnconfirmed)
        }
        val entries = List(records.length()) { index ->
            entry(records.get(index) as? JSONObject ?: throw IOException("Invalid history record"))
        }
        if (schema < 3L) return DownloadHistorySnapshot(entries)
        val plans = root.get("worklists") as? JSONArray ?: throw IOException("Invalid history worklists")
        require(plans.length() <= MAX_DOWNLOAD_HISTORY_ENTRIES)
        var rowCount = entries.size
        val worklists = List(plans.length()) { index ->
            val plan = plans.get(index) as? JSONObject ?: throw IOException("Invalid history worklist")
            require(plan.keySetCompat() == setOf("worklistId", "destination", "createdAtMillis", "rows"))
            val worklistId = id(plan.exactString("worklistId"))
            val destination = DownloadHistoryDestination.valueOf(plan.exactString("destination"))
            val created = plan.exactLong("createdAtMillis").also { require(it >= 0L) }
            val rows = plan.get("rows") as? JSONArray ?: throw IOException("Invalid history rows")
            require(rows.length() in 1..MAX_DOWNLOAD_HISTORY_ENTRIES)
            rowCount += rows.length()
            require(rowCount <= MAX_DOWNLOAD_HISTORY_ENTRIES)
            DownloadWorklist(worklistId, destination, created, List(rows.length()) { rowIndex ->
                val row = rows.get(rowIndex) as? JSONObject ?: throw IOException("Invalid history row")
                require(row.keySetCompat() == setOf("rowId", "filename", "receipt"))
                val rowId = id(row.exactString("rowId"))
                val filename = row.exactString("filename").also { require(it == sanitizeDownloadHistoryFilename(it)) }
                val receipt = if (row.get("receipt") === JSONObject.NULL) null else
                    entry(row.get("receipt") as? JSONObject ?: throw IOException("Invalid row receipt"))
                require(receipt == null || (receipt.filename == filename && receipt.destination == destination))
                DownloadWorklistRow(rowId, filename, receipt)
            })
        }
        return DownloadHistorySnapshot(entries, worklists)
    }

    /** Legacy fixture/caller support; the process store uses writeSnapshot for atomic migration. */
    fun write(entries: List<DownloadHistoryEntry>) = writeProtected(DownloadHistorySnapshot(entries), schema = 2)

    fun writeSnapshot(value: DownloadHistorySnapshot) = writeProtected(value, schema = 3)

    private fun writeProtected(value: DownloadHistorySnapshot, schema: Int) {
        try { writeJson(value, schema) }
        catch (failure: DownloadHistoryWriteException) { throw failure }
        catch (_: Exception) { throw DownloadHistoryWriteException(DownloadHistoryInstallation.NOT_INSTALLED) }
    }

    private fun writeJson(value: DownloadHistorySnapshot, schema: Int) {
        require(value.entries.size + value.worklists.sumOf { it.rows.size } <= MAX_DOWNLOAD_HISTORY_ENTRIES)
        require(value.worklists.all { it.rows.isNotEmpty() })
        fun entryJson(entry: DownloadHistoryEntry) = JSONObject().apply {
            put("receiptId", entry.receiptId)
            put("filename", entry.filename)
            put("destination", entry.destination.name)
            put("startedAtMillis", entry.startedAtMillis)
            put("finishedAtMillis", entry.finishedAtMillis ?: JSONObject.NULL)
            put("outcome", entry.outcome.name)
            put("cleanupUnconfirmed", entry.cleanupUnconfirmed)
        }
        val records = JSONArray()
        value.entries.forEach { records.put(entryJson(it)) }
        val root = JSONObject().put("schema", schema).put("records", records)
        if (schema == 3) {
            val worklists = JSONArray()
            for (worklist in value.worklists) {
                val rows = JSONArray()
                for (row in worklist.rows) rows.put(JSONObject().apply {
                    put("rowId", row.rowId)
                    put("filename", row.filename)
                    put("receipt", row.receipt?.let(::entryJson) ?: JSONObject.NULL)
                })
                worklists.put(JSONObject().put("worklistId", worklist.worklistId)
                    .put("destination", worklist.destination.name).put("createdAtMillis", worklist.createdAtMillis)
                    .put("rows", rows))
            }
            root.put("worklists", worklists)
        }
        val bytes = root.toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_DOWNLOAD_HISTORY_SNAPSHOT_BYTES)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("History directory unavailable")
        var temporary: File? = null
        var installation = DownloadHistoryInstallation.NOT_INSTALLED
        try {
            beforeWritePhase(DownloadHistoryWritePhase.BEFORE_TEMP_WRITE)
            temporary = File.createTempFile("history-", ".tmp", directory)
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                beforeWritePhase(DownloadHistoryWritePhase.BEFORE_SYNC)
                output.fd.sync()
            }
            beforeWritePhase(DownloadHistoryWritePhase.BEFORE_MOVE)
            // Unsupported atomic replacement is a history warning, never a truncating fallback.
            installation = DownloadHistoryInstallation.UNKNOWN
            atomicMove(temporary.toPath(), snapshot.toPath())
            installation = DownloadHistoryInstallation.INSTALLED
            beforeWritePhase(DownloadHistoryWritePhase.AFTER_MOVE)
        } catch (_: Exception) {
            throw DownloadHistoryWriteException(installation)
        } finally {
            // Cleanup is best effort and cannot turn an installed snapshot into a failed reset.
            try { temporary?.delete() } catch (_: Exception) {
                // Never include a filesystem path or exception message in history state.
            }
        }
    }
}

private fun JSONObject.keySetCompat(): Set<String> = keys().asSequence().toSet()

private fun JSONObject.exactLong(key: String): Long = when (val value = get(key)) {
    is Int -> value.toLong()
    is Long -> value
    else -> throw IOException("Invalid history integer")
}

private fun JSONObject.exactString(key: String): String =
    get(key) as? String ?: throw IOException("Invalid history string")

/** Small bounded grammar guard, including duplicate decoded keys; JSONObject handles values. */
private class StrictHistoryJson(private val text: String) {
    private var offset = 0
    fun validate() {
        value(0)
        whitespace()
        require(offset == text.length)
    }
    private fun whitespace() {
        while (offset < text.length && text[offset] in " \t\r\n") offset++
    }
    private fun take(char: Char): Boolean {
        whitespace()
        return if (offset < text.length && text[offset] == char) { offset++; true } else false
    }
    private fun value(depth: Int) {
        require(depth <= 8)
        whitespace()
        require(offset < text.length)
        when (text[offset]) {
            '{' -> {
                offset++
                val keys = HashSet<String>()
                if (take('}')) return
                do {
                    whitespace()
                    require(keys.add(string()))
                    require(take(':'))
                    value(depth + 1)
                } while (take(','))
                require(take('}'))
            }
            '[' -> {
                offset++
                if (take(']')) return
                do { value(depth + 1) } while (take(','))
                require(take(']'))
            }
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> {
                // All numbers in this schema are exact nonnegative integers. Reject exponent,
                // fraction, leading zero and integer overflow before JSONTokener can coerce them.
                val start = offset
                if (offset < text.length && text[offset] == '-') offset++
                require(offset < text.length && text[offset] in '0'..'9')
                if (text[offset] == '0') offset++ else while (offset < text.length && text[offset] in '0'..'9') offset++
                require(text.substring(start, offset).toLongOrNull() != null)
            }
        }
    }
    private fun literal(value: String) {
        require(text.startsWith(value, offset))
        offset += value.length
    }
    private fun string(): String {
        require(offset < text.length && text[offset++] == '"')
        val result = StringBuilder()
        while (offset < text.length) {
            val char = text[offset++]
            if (char == '"') return result.toString()
            require(char >= ' ')
            if (char != '\\') { result.append(char); continue }
            require(offset < text.length)
            when (val escaped = text[offset++]) {
                '"', '\\', '/' -> result.append(escaped)
                'b' -> result.append('\b')
                'f' -> result.append('\u000c')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                    require(offset + 4 <= text.length)
                    val hex = text.substring(offset, offset + 4)
                    require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                    result.append(hex.toInt(16).toChar())
                    offset += 4
                }
                else -> throw IOException("Invalid history escape")
            }
        }
        throw IOException("Invalid history string")
    }
}
