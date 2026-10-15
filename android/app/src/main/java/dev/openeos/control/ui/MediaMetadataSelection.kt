package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaItem

/** Frozen display snapshots only. Eligibility always comes from current loaded exact-ID items. */
class MediaMetadataSelection(items: List<CameraMediaItem>, val owner: MediaMetadataOwner) : AbstractList<CameraMediaItem>() {
    private val snapshot = items.distinctBy(CameraMediaItem::id).toList()
    override val size: Int get() = snapshot.size
    override fun get(index: Int): CameraMediaItem = snapshot[index]
}
