package com.jarvis.pineapple.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingGateTest {

    @Test
    fun wizardShowsWhenNoLocalModel() {
        val gate = OnboardingGate(hasLocalModel = { false })
        assertTrue(gate.shouldShowWizard())
    }

    @Test
    fun unconfirmedDownloadIsBlocked() {
        val gate = OnboardingGate(hasLocalModel = { false }, autoDownloadEnabled = { true })
        assertFalse(gate.allowNetworkDownload(userConfirmed = false))
        assertTrue(gate.allowNetworkDownload(userConfirmed = true))
    }

    @Test
    fun timeoutAndFailureUseChineseClose() {
        val gate = OnboardingGate(hasLocalModel = { false })
        assertEquals("模型下载超时", gate.timeoutError())
        assertEquals(30_000L, OnboardingGate.TIMEOUT_MS)
        assertEquals("模型下载失败", gate.failureError(null))
        assertEquals("模型下载失败：网络中断", gate.failureError("网络中断"))
    }
}
