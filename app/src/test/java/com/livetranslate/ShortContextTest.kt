package com.livetranslate

import com.livetranslate.translate.ShortContext
import org.junit.Assert.*
import org.junit.Test

class ShortContextTest {
    @Test fun startsWithinFiveSecondsThenPauseDoesNotExpire() {
        var time = 0L
        val history = ShortContext { time }
        history.committed("第一句")
        time = 4999
        assertEquals(listOf("第一句"), history.begin())
        time = 999999
        assertEquals(listOf("第一句"), history.begin())
        history.committed("第二句")
        time += 5001
        assertTrue(history.begin().isEmpty())
    }
    @Test fun draftsNeverCommitAndOnlyThreeEntries() {
        var time = 0L
        val history = ShortContext { time }
        history.begin()
        time++
        assertTrue(history.begin().isEmpty())
        for (i in 1..4) { history.committed("$i"); time++ }
        assertEquals(listOf("2", "3", "4"), history.begin())
        history.clear()
        assertTrue(history.begin().isEmpty())
    }
    @Test fun maximumFiveHundredCharacters() {
        val history = ShortContext { 0L }
        history.committed("a".repeat(300))
        history.committed("b".repeat(300))
        assertEquals(listOf("b".repeat(300)), history.begin())
    }
}
