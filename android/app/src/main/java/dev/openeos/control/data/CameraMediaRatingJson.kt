package dev.openeos.control.data

import org.json.JSONObject

/** Simulator and Bridge ratings are nullable exact integers; unknown is distinct from zero. */
internal fun JSONObject.normalizedMediaRating(): Int? =
    normalizedMediaMetadataInteger(opt("rating"), (0..5).toSet())
