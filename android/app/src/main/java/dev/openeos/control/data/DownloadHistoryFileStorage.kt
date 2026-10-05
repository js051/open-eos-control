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

internal enum class DownloadHistoryWritePhase { BEFORE_TEMP_WRITE, BEFORE_SYNC, BEFORE_MOVE }

/** Real-file storage; the callback only injects faults around actual writes in JVM tests. */
internal class DownloadHistoryFileStorage(
    private val directory: File,
    private val beforeWritePhase: (DownloadHistoryWritePhase) -> Unit = {},
) {
    private val snapshot get() = File(directory, DOWNLOAD_HISTORY_SNAPSHOT_NAME)

    fun read(): List<DownloadHistoryEntry> {
        if (Files.notExists(snapshot.toPath())) return emptyList()
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
        // JSONTokener treats an embedded NUL as end-of-input; it must not hide corrupt suffixes.
        require('\u0000' !in text)
        val tokener = JSONTokener(text)
        val root = tokener.nextValue() as? JSONObject ?: throw IOException("Invalid history snapshot")
        require(tokener.nextClean() == '\u0000')
        require(root.keySetCompat() == setOf("schema", "records"))
        require(root.exactLong("schema") == 1L)
        val records = root.get("records") as? JSONArray ?: throw IOException("Invalid history records")
        require(records.length() <= MAX_DOWNLOAD_HISTORY_ENTRIES)
        val ids = HashSet<String>()
        return List(records.length()) { index ->
            val record = records.get(index) as? JSONObject ?: throw IOException("Invalid history record")
            require(record.keySetCompat() == setOf(
                "receiptId", "filename", "destination", "startedAtMillis", "finishedAtMillis", "outcome",
            ))
            val id = record.exactString("receiptId")
            require(UUID.fromString(id).toString() == id && ids.add(id))
            val filename = record.exactString("filename")
            require(filename == sanitizeDownloadHistoryFilename(filename))
            val destination = DownloadHistoryDestination.valueOf(record.exactString("destination"))
            val outcome = DownloadHistoryOutcome.valueOf(record.exactString("outcome"))
            val started = record.exactLong("startedAtMillis").also { require(it >= 0L) }
            val finished = if (record.get("finishedAtMillis") === JSONObject.NULL) null
                else record.exactLong("finishedAtMillis").also { require(it >= 0L) }
            require((finished != null) == outcome.isTerminal())
            DownloadHistoryEntry(id, filename, destination, started, finished, outcome)
        }
    }

    fun write(entries: List<DownloadHistoryEntry>) {
        require(entries.size <= MAX_DOWNLOAD_HISTORY_ENTRIES)
        val records = JSONArray()
        for (entry in entries) {
            records.put(JSONObject().apply {
                put("receiptId", entry.receiptId)
                put("filename", entry.filename)
                put("destination", entry.destination.name)
                put("startedAtMillis", entry.startedAtMillis)
                put("finishedAtMillis", entry.finishedAtMillis ?: JSONObject.NULL)
                put("outcome", entry.outcome.name)
            })
        }
        val bytes = JSONObject().put("schema", 1).put("records", records)
            .toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_DOWNLOAD_HISTORY_SNAPSHOT_BYTES)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("History directory unavailable")
        var temporary: File? = null
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
            Files.move(temporary.toPath(), snapshot.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            // Cleanup is best effort and cannot turn an installed snapshot into a failed reset.
            try {
                temporary?.delete()
            } catch (_: Exception) {
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
