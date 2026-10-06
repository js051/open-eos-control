package dev.openeos.control.data

import java.util.Locale

/** A value snapshot of ObjectInfo already read by one bounded listing; never queries a camera. */
internal class PtpObservedMediaFolders private constructor(
    private val storageIds: Set<Long>,
    private val objects: Map<Long, PtpObjectInfo>,
) {
    fun folderFor(item: PtpObjectInfo): CameraMediaFolder? {
        if (item.storageId !in storageIds || item.storageId !in 1L until UINT32_MAX) return null
        if (item.handle !in 1L until UINT32_MAX || item.objectFormat == PtpObjectFormat.ASSOCIATION) return null
        if (item.associationType != 0) return null
        val names = mutableListOf<String>()
        val visited = mutableSetOf(item.handle)
        var parent = item.parentObject
        var labelLength = "Storage ${item.storageId.hex()}".length
        // ObjectInfo.ParentObject uses 0 for a storage-root object. This is not the
        // GetObjectHandles command's 0xFFFFFFFF root-only selector (0 means all there).
        while (parent != 0L) {
            if (parent !in 1L until UINT32_MAX || !visited.add(parent)) return null
            val association = objects[parent] ?: return null
            if (association.storageId != item.storageId || association.objectFormat != PtpObjectFormat.ASSOCIATION) return null
            // Standard GenericFolder only; other associations are not filesystem directories.
            if (association.associationType != 1 || !association.filename.isFolderComponent()) return null
            labelLength += 3 + association.filename.length
            if (labelLength > 1024) return null
            names += association.filename
            parent = association.parentObject
        }
        return cameraMediaFolderOrNull(
            id = "ptp:${item.storageId.hex()}:${item.parentObject.hex()}",
            label = (listOf("Storage ${item.storageId.hex()}") + names.asReversed()).joinToString(" / "),
        )
    }

    class Builder(storageIds: List<Long>) {
        private val storages = storageIds.toSet()
        private val objects = mutableMapOf<Long, PtpObjectInfo>()

        fun observe(info: PtpObjectInfo) {
            if (info.objectFormat == PtpObjectFormat.ASSOCIATION) objects[info.handle] = info
            else objects.remove(info.handle)
        }

        fun snapshot() = PtpObservedMediaFolders(storages, objects.toMap())
    }

    companion object {
        val Empty = PtpObservedMediaFolders(emptySet(), emptyMap())
    }
}

private fun String.isFolderComponent(): Boolean =
    isNotBlank() && this == trim() && this != "." && this != ".." &&
        none { it.isISOControl() || it == '/' || it == '\\' }

private fun Long.hex(): String = toString(16).uppercase(Locale.ROOT).padStart(8, '0')
