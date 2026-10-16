package dev.openeos.control.data

import org.json.JSONObject

/** Do not trim, coerce, or discard explicit MIME declarations before the shared safety gate. */
internal fun JSONObject.cameraMediaContentType(): String? =
    cameraMediaContentTypeFromAliases(CAMERA_MEDIA_MIME_ALIASES.filter(::has).associateWith { key ->
        if (isNull(key)) null else opt(key)
    })
