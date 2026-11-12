package com.jarvis.pineapple.load

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadEscalationTest {

    @Test
    fun thirtyRoundsStrictlyEscalateAndKeepCoreThenFull() {
        LoadProfile.assertEscalates()
        assertTrue(LoadProfile.forRound(1).coreOnly)
        assertFalse(LoadProfile.forRound(2).coreOnly)
        assertFalse(LoadProfile.forRound(30).coreOnly)
        assertTrue(LoadProfile.forRound(11).workers > 1)
        assertTrue(LoadProfile.forRound(21).memories > LoadProfile.forRound(20).memories)
        assertTrue(LoadProfile.forRound(30).weight() > LoadProfile.forRound(1).weight())
    }
}
