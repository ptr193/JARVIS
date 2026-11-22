package com.jarvis.pineapple.security

import org.junit.Assert.assertEquals
import org.junit.Test

class SecurityManagerTest {

    @Test
    fun decideResponseMapsSeverityToAction() {
        assertEquals(
            SecurityManager.ResponseAction.NONE,
            SecurityManager.decideResponse(SecurityManager.RiskLevel.LOW)
        )
        assertEquals(
            SecurityManager.ResponseAction.LOCK,
            SecurityManager.decideResponse(SecurityManager.RiskLevel.MEDIUM)
        )
        assertEquals(
            SecurityManager.ResponseAction.LOCKDOWN,
            SecurityManager.decideResponse(SecurityManager.RiskLevel.HIGH)
        )
        assertEquals(
            SecurityManager.ResponseAction.WIPE,
            SecurityManager.decideResponse(SecurityManager.RiskLevel.CRITICAL)
        )
    }
}
