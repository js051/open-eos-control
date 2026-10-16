package dev.openeos.control.data

import org.junit.Assert.*
import org.junit.Test

class MediaMetadataItemTest {
    @Test fun partialOrUnrelatedMutationReadbackNeverReplacesCurrentEligibilityOrOtherValues() {
        val current = CameraMediaItem("exact", "CURRENT.JPG", "image", protected = false, archived = true,
            rating = 2, rotationDegrees = 270, ratingWritable = false, protectionWritable = false,
            archiveWritable = false, rotationWritable = false)
        val response = CameraMediaItem("exact", "UNTRUSTED.JPG", "image", protected = true, archived = false,
            rating = 5, rotationDegrees = 0, ratingWritable = true, protectionWritable = true,
            archiveWritable = true, rotationWritable = true)
        for ((target, expected) in listOf(
            MediaMetadataTarget(MediaMetadataField.PROTECTION, 1) to current.copy(protected = true),
            MediaMetadataTarget(MediaMetadataField.ARCHIVE, 0) to current.copy(archived = false),
            MediaMetadataTarget(MediaMetadataField.RATING, 5) to current.copy(rating = 5),
            MediaMetadataTarget(MediaMetadataField.ROTATION, 0) to current.copy(rotationDegrees = 0),
        )) {
            assertEquals(expected, current.withVerifiedMetadata(target, response))
            assertFalse(current.withVerifiedMetadata(target, response).metadataWritable(target.field))
        }
        val partial = CameraMediaItem("exact", "PARTIAL.JPG", "image", protected = true)
        assertEquals(current.copy(protected = true), current.withVerifiedMetadata(MediaMetadataTarget(MediaMetadataField.PROTECTION, 1), partial))
    }
    @Test fun missingMismatchedOrWrongIdTargetCannotBeMerged() {
        val item = CameraMediaItem("exact", "CURRENT.JPG", "image", rating = 2, ratingWritable = false)
        val target = MediaMetadataTarget(MediaMetadataField.RATING, 3)
        for (readback in listOf(item.copy(rating = null), item, item.copy(id = "other", rating = 3))) {
            assertNotNull(runCatching { item.withVerifiedMetadata(target, readback) }.exceptionOrNull())
        }
    }
    @Test fun currentExactIdEvidenceOverridesStaleCallerButFreshDetailStillWorks() {
        val stale = CameraMediaItem("exact", "SAME.JPG", "image", ratingWritable = true,
            protectionWritable = true, archiveWritable = true, rotationWritable = true, archived = false)
        val current = stale.copy(ratingWritable = false, protectionWritable = false,
            archiveWritable = false, rotationWritable = false, archived = null)
        val unrelated = current.copy(id = "EXACT")
        assertEquals(current, currentMediaMetadataItem(stale, listOf(unrelated, current), stale, stale))
        MediaMetadataField.entries.forEach { assertFalse(currentMediaMetadataItem(stale, listOf(current)).metadataWritable(it)) }
        assertEquals(current, currentMediaMetadataItem(stale, listOf(unrelated), current, stale))
        assertEquals(current, currentMediaMetadataItem(stale, emptyList(), null, current))
        assertEquals(stale, currentMediaMetadataItem(stale, listOf(unrelated)))
        assertEquals(stale, currentMediaMetadataItem(stale, emptyList()))
    }

}
