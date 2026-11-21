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
    fun wizardHiddenWhenLocalModelExists() {
        val gate = OnboardingGate(hasLocalModel = { true })
        assertFalse(gate.shouldShowWizard())
    }

    @Test
    fun downloadTimeoutIsFiniteAndFailuresUseChineseClose() {
        assertTrue(OnboardingGate.TIMEOUT_MS > 0L)
        assertEquals(7_200_000L, OnboardingGate.TIMEOUT_MS)
        assertEquals("模型下载超时", OnboardingGate.timeoutError())
        assertEquals("模型下载失败", OnboardingGate.failureError(null))
        assertEquals("模型下载失败：网络中断", OnboardingGate.failureError("网络中断"))
    }
}
