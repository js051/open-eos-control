package dev.openeos.control.data

import dev.openeos.control.data.UsbPtpObservedFoldersTest.Companion.folder
import dev.openeos.control.data.UsbPtpObservedFoldersTest.Companion.media
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PtpObservedMediaFoldersTest {
    @Test
    fun rootDatasetZeroIsKnownButCommandRootSelectorIsNotDatasetEvidence() {
        val observed = PtpObservedMediaFolders.Builder(listOf(STORAGE)).snapshot()
        assertEquals("Storage 00010001", observed.folderFor(media(42))?.label)
        assertNull(observed.folderFor(media(42, parent = UINT32_MAX)))
        assertNull(observed.folderFor(media(42, storage = 0)))
        assertNull(observed.folderFor(media(42, storage = UINT32_MAX)))
        assertNull(observed.folderFor(media(42, storage = STORAGE + 1)))
        assertNull(observed.folderFor(media(0)))
        assertNull(observed.folderFor(media(UINT32_MAX)))
        assertNull(observed.folderFor(media(42).copy(associationType = 1)))
    }

    @Test
    fun missingBrokenCyclicCrossStorageAndNonFolderParentsStayUnknown() {
        val parents = listOf(
            emptyList(),
            listOf(folder(10, "DCIM", parent = 99)),
            listOf(folder(10, "DCIM", parent = 10)),
            listOf(folder(10, "DCIM", parent = 11), folder(11, "Loop", parent = 10)),
            listOf(folder(10, "DCIM", storage = STORAGE + 1)),
            listOf(media(10)),
            listOf(folder(10, "Album").copy(associationType = 2)),
            listOf(folder(10, "Undefined").copy(associationType = 0)),
            listOf(folder(10, "DCIM", parent = UINT32_MAX)),
        )
        parents.forEach { chain ->
            val builder = PtpObservedMediaFolders.Builder(listOf(STORAGE, STORAGE + 1))
            chain.forEach(builder::observe)
            assertNull(chain.toString(), builder.snapshot().folderFor(media(42, parent = 10)))
        }
    }

    @Test
    fun unsafeOrOverlongLabelsStayUnknownInsteadOfCreatingAmbiguousPaths() {
        listOf("", " ", ".", "..", " DCIM", "DCIM ", "DCIM/100EOS", "DCIM\\100EOS", "a\u0000b", "a\u0085b", "x".repeat(1025))
            .forEach { name ->
                val builder = PtpObservedMediaFolders.Builder(listOf(STORAGE))
                builder.observe(folder(10, name))
                assertNull(builder.snapshot().folderFor(media(42, parent = 10)))
            }
        val builder = PtpObservedMediaFolders.Builder(listOf(STORAGE))
        builder.observe(folder(10, "a".repeat(600)))
        builder.observe(folder(11, "b".repeat(600), parent = 10))
        assertNull(builder.snapshot().folderFor(media(42, parent = 11)))
    }

    @Test
    fun sameNameFoldersHaveDistinctStorageAndParentIdentity() {
        val builder = PtpObservedMediaFolders.Builder(listOf(STORAGE, STORAGE + 1))
        builder.observe(folder(10, "DCIM"))
        builder.observe(folder(11, "DCIM"))
        builder.observe(folder(12, "DCIM", storage = STORAGE + 1))
        val observed = builder.snapshot()
        val first = observed.folderFor(media(42, parent = 10))!!
        val sameName = observed.folderFor(media(43, parent = 11))!!
        val otherStorage = observed.folderFor(media(44, parent = 12, storage = STORAGE + 1))!!
        assertNotEquals(first.id, sameName.id)
        assertNotEquals(first.id, otherStorage.id)
        assertNotEquals(first.label, otherStorage.label)
    }

    @Test
    fun builderChangesNeverMutatePublishedSnapshot() {
        val builder = PtpObservedMediaFolders.Builder(listOf(STORAGE))
        val before = builder.snapshot()
        builder.observe(folder(10, "DCIM"))
        val known = builder.snapshot()
        builder.observe(folder(10, "CHANGED"))
        assertNull(before.folderFor(media(42, parent = 10)))
        assertEquals("Storage 00010001 / DCIM", known.folderFor(media(42, parent = 10))?.label)
        assertEquals("Storage 00010001 / CHANGED", builder.snapshot().folderFor(media(42, parent = 10))?.label)
    }

    @Test
    fun nonAssociationReplacementInvalidatesParentOnlyInNewSnapshot() {
        val builder = PtpObservedMediaFolders.Builder(listOf(STORAGE))
        builder.observe(folder(10, "DCIM"))
        val previous = builder.snapshot()
        builder.observe(media(10))
        assertNull(builder.snapshot().folderFor(media(42, parent = 10)))
        assertEquals("Storage 00010001 / DCIM", previous.folderFor(media(42, parent = 10))?.label)
    }

    companion object { private const val STORAGE = 0x00010001L }
}
