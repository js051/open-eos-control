package dev.openeos.control.ui

/** A retired screen must not acquire a new transfer, preview or cancellation owner. */
internal class MediaScreenActionGate(
    private val owner: MediaMetadataOwner,
    private val currentOwner: () -> MediaMetadataOwner,
) {
    fun dispatch(action: () -> Unit) {
        val current = currentOwner()
        if (owner.matches(current.connection, current.generation)) action()
    }
}
