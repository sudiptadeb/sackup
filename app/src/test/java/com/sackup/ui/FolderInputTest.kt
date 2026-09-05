package com.sackup.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FolderInputTest {

    @Test
    fun `plain name is returned unchanged`() {
        assertEquals("Recordings", normalizeFolderInput("Recordings"))
    }

    @Test
    fun `surrounding whitespace and slashes are trimmed`() {
        assertEquals("Recordings", normalizeFolderInput("  /Recordings/  "))
        assertEquals("Recordings", normalizeFolderInput("///Recordings///"))
    }

    @Test
    fun `nested path keeps inner separators`() {
        assertEquals(
            "Android/media/com.whatsapp/WhatsApp/Media",
            normalizeFolderInput("/Android/media/com.whatsapp/WhatsApp/Media/")
        )
    }

    @Test
    fun `repeated separators and whitespace around segments are collapsed`() {
        assertEquals("DCIM/Camera", normalizeFolderInput("DCIM // Camera"))
        assertEquals("DCIM/Camera", normalizeFolderInput(" DCIM / Camera "))
    }

    @Test
    fun `backslashes are treated as separators`() {
        assertEquals("DCIM/Camera", normalizeFolderInput("DCIM\\Camera"))
    }

    @Test
    fun `dot segments are dropped`() {
        assertEquals("DCIM", normalizeFolderInput("./DCIM/."))
        assertEquals("DCIM", normalizeFolderInput("../DCIM"))
        assertEquals("DCIM/Camera", normalizeFolderInput("DCIM/./Camera"))
    }

    @Test
    fun `blank input yields null`() {
        assertNull(normalizeFolderInput(""))
        assertNull(normalizeFolderInput("   "))
        assertNull(normalizeFolderInput("/"))
        assertNull(normalizeFolderInput(" / / "))
        assertNull(normalizeFolderInput(".."))
    }

    @Test
    fun `addFolder appends a new normalised folder`() {
        assertEquals(listOf("DCIM", "Pictures"), addFolder(listOf("DCIM"), " /Pictures/ "))
    }

    @Test
    fun `addFolder ignores duplicates case-insensitively`() {
        val current = listOf("DCIM")
        assertSame(current, addFolder(current, "dcim"))
        assertSame(current, addFolder(current, "/DCIM/"))
    }

    @Test
    fun `addFolder ignores blank input`() {
        val current = listOf("DCIM")
        assertSame(current, addFolder(current, "   "))
    }
}
