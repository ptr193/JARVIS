package com.jarvis.pineapple.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun scanIrisReportsUnsupportedAndNeverFakesSuccess() {
        val result = SecurityManager.irisScanResult()
        // 普通设备无 IR 摄像头，必须如实报告不支持，绝不伪造 irisCode/成功。
        assertFalse(result is SecurityManager.IrisScanResult.Success)
        assertTrue(result is SecurityManager.IrisScanResult.Unsupported)
        val reason = (result as SecurityManager.IrisScanResult.Unsupported).reason
        assertTrue(reason.isNotBlank())
        assertEquals(SecurityManager.UNSUPPORTED_IRIS_REASON, reason)
    }
}
