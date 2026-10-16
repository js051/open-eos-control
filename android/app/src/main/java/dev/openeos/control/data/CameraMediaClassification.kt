package dev.openeos.control.data

/** Display family only: this never grants decoder, transport, or delivery capability. */
enum class CameraMediaClassification { PHOTO, VIDEO, AUDIO, OTHER, UNKNOWN, CONFLICT }

fun classifyCameraMedia(kind: String, name: String, contentType: String? = null): CameraMediaClassification {
    if (name.isBlank()) return CameraMediaClassification.UNKNOWN
    val mime = contentType?.substringBefore(';')?.trim()?.asciiLowercase().orEmpty()
    if (contentType?.any(Char::isISOControl) == true || (mime.isNotEmpty() && !MEDIA_MIME.matches(mime))) {
        return CameraMediaClassification.UNKNOWN
    }
    val kindFamily = when (kind.trim().asciiLowercase()) {
        "image", "photo", "jpeg", "jpg", "raw" -> CameraMediaClassification.PHOTO
        "video", "movie" -> CameraMediaClassification.VIDEO
        "audio" -> CameraMediaClassification.AUDIO
        "text", "document" -> CameraMediaClassification.OTHER
        else -> null
    }
    val extensionFamily = when (name.substringAfterLast('.', "").asciiLowercase()) {
        in PHOTO_EXTENSIONS -> CameraMediaClassification.PHOTO
        in VIDEO_EXTENSIONS -> CameraMediaClassification.VIDEO
        in AUDIO_EXTENSIONS -> CameraMediaClassification.AUDIO
        else -> null
    }
    val mimeFamily = when {
        mime.isEmpty() || mime == "application/octet-stream" -> null
        mime.startsWith("image/") -> CameraMediaClassification.PHOTO
        mime.startsWith("video/") -> CameraMediaClassification.VIDEO
        mime.startsWith("audio/") -> CameraMediaClassification.AUDIO
        else -> CameraMediaClassification.OTHER
    }
    val families = setOfNotNull(kindFamily, extensionFamily, mimeFamily)
    return when (families.size) {
        0 -> CameraMediaClassification.UNKNOWN
        1 -> families.single()
        else -> CameraMediaClassification.CONFLICT
    }
}

val CameraMediaItem.mediaClassification: CameraMediaClassification
    get() = classifyCameraMedia(kind, name, contentType)

val CameraMediaItem.isPhotoMedia: Boolean
    get() = mediaClassification == CameraMediaClassification.PHOTO

/** Video poster thumbnails still require a supported endpoint and image-payload validation. */
val CameraMediaItem.isVisualMedia: Boolean
    get() = mediaClassification in setOf(CameraMediaClassification.PHOTO, CameraMediaClassification.VIDEO)

/** Conservative automatic-delivery cohort, intentionally narrower than the display family. */
val CameraMediaItem.isAutomaticJpegEligible: Boolean
    get() = id.isNotBlank() && name.isNotBlank() && kind == "image" &&
        '/' !in name && '\\' !in name && name.lastIndexOf('.') > 0 &&
        name.substringAfterLast('.', "").asciiLowercase() in setOf("jpg", "jpeg") && isPhotoMedia &&
        (contentType.isNullOrEmpty() || contentType.substringBefore(';').trim().asciiLowercase() == "image/jpeg")

fun automaticJpegMetadataAdmitted(original: CameraMediaItem, returned: CameraMediaItem, fresh: Boolean): Boolean =
    fresh && original.id == returned.id && original.name == returned.name &&
        original.isAutomaticJpegEligible && returned.isAutomaticJpegEligible

internal val CAMERA_MEDIA_MIME_ALIASES = listOf("content_type", "contentType", "contenttype", "mimeType", "mimetype")
internal data class CameraMediaMimeResolution(val valid: Boolean, val value: String?)

/** Exact declarations matter: even null/empty or case-only alias disagreements fail closed. */
internal fun resolveCameraMediaMimeAliases(metadata: Map<String, Any?>): CameraMediaMimeResolution {
    val declarations = CAMERA_MEDIA_MIME_ALIASES.filter(metadata::containsKey).map { metadata[it] }
    if (declarations.isEmpty()) return CameraMediaMimeResolution(true, null)
    if (declarations.any { it != null && it !is String } || declarations.distinct().size != 1) {
        return CameraMediaMimeResolution(false, null)
    }
    return CameraMediaMimeResolution(true, declarations.first() as? String)
}

/** Deliberately invalid MIME syntax retains alias rejection evidence in the existing DTO. */
internal fun cameraMediaContentTypeFromAliases(metadata: Map<String, Any?>): String? =
    resolveCameraMediaMimeAliases(metadata).let { if (it.valid) it.value else "invalid-media-type" }

/** Preserve the previous file transport cohort; classification alone must not expand it. */
internal fun cameraMediaHasVideoTransportHint(kind: String, name: String): Boolean =
    kind.equals("video", ignoreCase = true) || name.substringAfterLast('.', "").asciiLowercase() in VIDEO_EXTENSIONS

private fun String.asciiLowercase(): String = map { if (it in 'A'..'Z') it + 32 else it }.joinToString("")
private val MEDIA_MIME = Regex("[a-z0-9!#$&^_.+\\-]+/[a-z0-9!#$&^_.+\\-]+")
private val PHOTO_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "tif", "tiff", "avif", "cr2", "cr3", "crw", "dng")
private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "avi", "mkv")
private val AUDIO_EXTENSIONS = setOf("wav", "mp3", "aac", "m4a", "flac", "ogg", "opus", "aif", "aiff", "wma")
