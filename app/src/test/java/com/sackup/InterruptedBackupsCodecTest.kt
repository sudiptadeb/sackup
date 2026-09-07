package com.sackup

import com.sackup.service.InterruptedBackups
import org.junit.Assert.assertEquals
import org.junit.Test

class InterruptedBackupsCodecTest {

    @Test
    fun encode_joinsDistinctIds() {
        assertEquals("1,2,3", InterruptedBackups.encode(listOf(1L, 2L, 3L)))
        assertEquals("7,9", InterruptedBackups.encode(listOf(7L, 9L, 7L)))
        assertEquals("", InterruptedBackups.encode(emptyList()))
    }

    @Test
    fun parse_roundTripsAndIgnoresJunk() {
        assertEquals(listOf(1L, 2L, 3L), InterruptedBackups.parse("1,2,3"))
        assertEquals(listOf(4L, 5L), InterruptedBackups.parse(" 4 , x, 5 ,,"))
        assertEquals(emptyList<Long>(), InterruptedBackups.parse(null))
        assertEquals(emptyList<Long>(), InterruptedBackups.parse(""))
        assertEquals(listOf(8L), InterruptedBackups.parse("8,8"))
    }
}
