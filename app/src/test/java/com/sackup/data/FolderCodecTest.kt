package com.sackup.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderCodecTest {

    @Test
    fun roundTrip_preservesOrderAndValues() {
        val folders = listOf("DCIM", "Pictures", "WhatsApp/Media")
        assertEquals(folders, decodeFolders(encodeFolders(folders)))
    }

    @Test
    fun encode_producesJsonArray() {
        assertEquals("[\"DCIM\",\"Pictures\"]", encodeFolders(listOf("DCIM", "Pictures")))
        assertEquals("[]", encodeFolders(emptyList()))
    }

    @Test
    fun decode_malformedJsonYieldsEmptyList() {
        assertTrue(decodeFolders("[\"DCIM\"").isEmpty())
        assertTrue(decodeFolders("not json at all").isEmpty())
        assertTrue(decodeFolders("{\"a\":1}").isEmpty())
    }

    @Test
    fun decode_nullLiteralAndNullYieldEmptyList() {
        assertTrue(decodeFolders("null").isEmpty())
        assertTrue(decodeFolders(null).isEmpty())
        assertTrue(decodeFolders("").isEmpty())
        assertTrue(decodeFolders("   ").isEmpty())
    }

    @Test
    fun decode_dropsBlankAndNullEntries() {
        assertEquals(listOf("DCIM"), decodeFolders("[\"DCIM\", \"\", \"   \", null]"))
    }

    @Test
    fun decode_trimsLeadingAndTrailingSlashes() {
        assertEquals(
            listOf("DCIM", "Pictures/Screenshots"),
            decodeFolders("[\"/DCIM/\", \"Pictures/Screenshots/\"]")
        )
    }

    @Test
    fun encode_trimsSlashesAndDropsBlankEntries() {
        assertEquals("[\"DCIM\",\"Music\"]", encodeFolders(listOf("/DCIM/", "", " ", "Music/")))
    }

    @Test
    fun folderList_extensionDecodesEntity() {
        val group = BackupGroup(name = "Images", phoneFolders = encodeFolders(listOf("DCIM", "Pictures")))
        assertEquals(listOf("DCIM", "Pictures"), group.folderList())
    }

    @Test
    fun defaultGroups_areImagesDocumentsMusic() {
        val defaults = BackupRepository.defaultGroups()
        assertEquals(listOf("Images", "Documents", "Music"), defaults.map { it.name })
        assertEquals(listOf("DCIM", "Pictures"), defaults[0].folderList())
        assertEquals(listOf("Documents"), defaults[1].folderList())
        assertEquals(listOf("Music"), defaults[2].folderList())
    }
}
