package com.sackup.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DriveConnectedDialogTest {

    @Test
    fun freshConnectOffersToBackUpEverything() {
        assertEquals("USB drive connected", connectPromptTitle(interrupted = false))
        assertEquals("Back up everything now? (3 backups)", connectPromptBody(interrupted = false, groupCount = 3))
        assertEquals("Back up everything now? (1 backup)", connectPromptBody(interrupted = false, groupCount = 1))
        assertEquals("Back up now", connectPromptButton(interrupted = false))
    }

    @Test
    fun interruptedConnectOffersToContinue() {
        assertEquals("Welcome back", connectPromptTitle(interrupted = true))
        assertEquals(
            "Your last backup was interrupted. Continue where it left off?",
            connectPromptBody(interrupted = true, groupCount = 2)
        )
        assertEquals("Continue", connectPromptButton(interrupted = true))
    }
}
