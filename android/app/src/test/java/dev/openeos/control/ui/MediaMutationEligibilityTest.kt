package dev.openeos.control.ui

import dev.openeos.control.data.MediaMetadataField
import dev.openeos.control.data.normalizedMediaMetadataBoolean
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaMutationEligibilityTest {
    private fun admitted(field: String, requestedId: String, loaded: Boolean, supported: Boolean, item: Map<String, Any?>): Boolean {
        val operation = when(field) {
            "rating" -> MediaMetadataField.RATING
            "protection" -> MediaMetadataField.PROTECTION
            "rotation" -> MediaMetadataField.ROTATION
            "archive" -> MediaMetadataField.ARCHIVE
            else -> error("Unknown field")
        }
        val writableKey = if (field == "protection") "protectionWritable" else "${field}Writable"
        return mediaMetadataPreflight(operation, supported, requestedId, if (loaded) item["id"] as? String else null,
            normalizedMediaMetadataBoolean(item["archived"]), normalizedMediaMetadataBoolean(item[writableKey])) == null
    }
    @Test fun all25EligibilityVectorsUseCurrentLoadedExactIdentityCapabilityAndWritableVeto() {
        val vectors = loadMediaMutationEligibilityFixture().vectors
        assertEquals(25, vectors.size)
        vectors.forEach { assertEquals(it.id, it.eligible, admitted(it.field, it.requestedId, it.loaded, it.supported, it.item)) }
    }
    @Test fun bothStaleSelectionSnapshotsLoseToTheCurrentLoadedRow() {
        val vectors = loadMediaMutationEligibilityFixture().snapshots
        assertEquals(2, vectors.size)
        vectors.forEach { assertEquals(it.id, it.eligible, admitted(it.field, it.selected.getValue("id") as String, true, true, it.loaded)) }
    }
}
