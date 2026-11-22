package com.jarvis.pineapple.load

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadEscalationTest {

    @Test
    fun fortyRoundsStrictlyEscalateAndKeepCoreThenFull() {
        LoadProfile.assertEscalates()
        assertTrue(LoadProfile.forRound(1).coreOnly)
        assertFalse(LoadProfile.forRound(2).coreOnly)
        assertFalse(LoadProfile.forRound(40).coreOnly)
        assertTrue(LoadProfile.forRound(11).workers > 1)
        assertTrue(LoadProfile.forRound(21).memories > LoadProfile.forRound(20).memories)
        assertTrue(LoadProfile.forRound(40).memories > LoadProfile.forRound(30).memories)
        assertTrue(LoadProfile.forRound(40).workers * LoadProfile.forRound(40).createsPerWorker >
            LoadProfile.forRound(30).workers * LoadProfile.forRound(30).createsPerWorker)
        assertTrue(LoadProfile.forRound(40).payloadChars > LoadProfile.forRound(30).payloadChars)
        assertTrue(LoadProfile.forRound(40).weight() > LoadProfile.forRound(1).weight())

        val peak = LoadProfile.forRound(40)
        assertEquals(40, peak.conversations)
        assertEquals(40, peak.messagesPerConversation)
        assertEquals(2_400, peak.payloadChars)
        assertEquals(2_600, peak.memories)
        assertEquals(8, peak.workers)
        assertEquals(30, peak.createsPerWorker)
        assertEquals(40, peak.chatTurns)
        assertEquals(40, peak.vmCommandRepeats)
        assertEquals(40, peak.vncRects)
        assertEquals(43_008, peak.modelPayloadBytes)
        assertEquals(540, peak.auditEntries)
    }
}
