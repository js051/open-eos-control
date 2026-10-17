package dev.openeos.control.data

/** Closures retain the exact Bridge client, session, and owner reserved before UI suspension. */
class BridgeBulbAccess internal constructor(
    val start: suspend () -> CameraStatus,
    val stop: suspend () -> CameraStatus?,
    val releaseRequired: () -> Boolean,
    val hasFreshReleaseObservation: () -> Boolean = { false },
    val retire: () -> Unit = {},
)
