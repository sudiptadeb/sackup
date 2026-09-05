package com.sackup.service

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

class CopyErrorsTest {

    @Test
    fun sourceMissingIsPlainLanguage() {
        val e = SourceMissingException(FileNotFoundException("open failed: ENOENT"))
        assertEquals(SOURCE_MISSING_MESSAGE, friendlyCopyError(e))
    }

    @Test
    fun driveFullIsDetectedFromEnospcAndText() {
        assertEquals(DRIVE_FULL_MESSAGE, friendlyCopyError(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(DRIVE_FULL_MESSAGE, friendlyCopyError(IOException("no space left on device")))
        // Also when buried in the cause chain
        val wrapped = RuntimeException("write failed", IOException("ENOSPC"))
        assertEquals(DRIVE_FULL_MESSAGE, friendlyCopyError(wrapped))
    }

    @Test
    fun readOnlyIsDetected() {
        assertEquals(DRIVE_READ_ONLY_MESSAGE, friendlyCopyError(IOException("open failed: EROFS (Read-only file system)")))
        assertEquals(DRIVE_READ_ONLY_MESSAGE, friendlyCopyError(IOException("Read-only file system")))
    }

    @Test
    fun verificationMessageIsPassedThrough() {
        val e = CopyVerificationException("The drive saved \"a.jpg\" as \"a (1).jpg\"")
        assertEquals("The drive saved \"a.jpg\" as \"a (1).jpg\"", friendlyCopyError(e))
    }

    @Test
    fun otherExceptionsFallBackToMessageOrClassName() {
        assertEquals("boom", friendlyCopyError(IllegalStateException("boom")))
        assertEquals("IllegalStateException", friendlyCopyError(IllegalStateException()))
        // A FileNotFoundException that did not come from the source is not blamed on the phone
        assertEquals("drive gone", friendlyCopyError(FileNotFoundException("drive gone")))
    }

    @Test
    fun mimeTypesAreMappedByExtensionCaseInsensitively() {
        assertEquals("image/jpeg", BackupEngine.getMimeType("IMG_001.JPG"))
        assertEquals("image/jpeg", BackupEngine.getMimeType("photo.jpeg"))
        assertEquals("video/mp4", BackupEngine.getMimeType("clip.mp4"))
        assertEquals("application/pdf", BackupEngine.getMimeType("doc.pdf"))
        assertEquals("application/octet-stream", BackupEngine.getMimeType("archive.tar.xz"))
        assertEquals("application/octet-stream", BackupEngine.getMimeType("noextension"))
    }
}
