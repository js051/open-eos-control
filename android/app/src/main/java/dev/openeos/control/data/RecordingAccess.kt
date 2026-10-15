package dev.openeos.control.data

/** Captured backend/session handle; it must never resolve a replacement camera. */
class RecordingAccess(val dispatch: suspend (RecordingCommand) -> CameraStatus, val read: suspend () -> RecordingObservation?)
