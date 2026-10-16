package dev.openeos.control.ui

internal data class CubeLutUtf8Vector(
    val id: String,
    val bytes: ByteArray,
    val accepted: Boolean,
    val normalizedText: String?,
)
