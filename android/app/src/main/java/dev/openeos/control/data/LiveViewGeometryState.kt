package dev.openeos.control.data

/** Logical ownership only; this does not prove physical frame presentation or camera timing. */
internal class LiveViewGeometryState<T> {
    data class Read(val revision: Long, val sequence: Long)
    data class Snapshot<T>(val value: T, val read: Read)
    private var revision = 0L
    private var sequence = 0L
    private var changing = false
    private var retired = false
    private var latest: Snapshot<T>? = null

    @Synchronized fun reset() {
        revision += 1; changing = false; retired = false; latest = null
    }
    @Synchronized fun retire() {
        revision += 1; changing = false; retired = true; latest = null
    }
    @Synchronized fun owner(): Long {
        check(!retired && !changing) { "Live View position metadata is unavailable during a session or magnification change." }
        return revision
    }
    @Synchronized fun requireRevision(owner: Long) {
        check(!retired && revision == owner) { "Live View geometry owner changed." }
    }
    @Synchronized fun requireOwner(owner: Long) {
        requireRevision(owner)
        check(!changing) { "Live View magnification is still changing." }
    }
    @Synchronized fun beginMutation(owner: Long): Long {
        requireOwner(owner)
        revision += 1; changing = true; latest = null
        return revision
    }
    @Synchronized fun finishMutation(owner: Long) {
        if (revision != owner || retired) return
        revision += 1; changing = false; latest = null
    }
    @Synchronized fun beginRead(owner: Long): Read {
        requireOwner(owner)
        sequence += 1; latest = null
        return Read(revision, sequence)
    }
    @Synchronized fun publish(read: Read, value: T?): Boolean {
        if (retired || changing || read.revision != revision || read.sequence != sequence) return false
        latest = value?.let { Snapshot(it, read) }
        return value != null
    }
    @Synchronized fun snapshot(): Snapshot<T>? = if (retired || changing) null else latest
    @Synchronized fun requireSnapshot(snapshot: Snapshot<T>) {
        requireOwner(snapshot.read.revision)
        check(latest === snapshot) { "Live View position metadata changed before coordinate dispatch." }
    }
}
