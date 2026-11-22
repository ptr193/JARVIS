package com.jarvis.pineapple.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntrusionDetectorTest {

    @Test
    fun evaluateCleanFlagsReportsNoRisk() {
        val report = IntrusionDetector.evaluate(
            IntrusionFlags(false, false, false, false)
        )
        assertTrue(report.findings.isEmpty())
        assertFalse(report.isCompromised)
        assertTrue(report.summary.contains("未发现"))
    }

    @Test
    fun evaluateAdbEnabledIsHighAndCompromised() {
        val report = IntrusionDetector.evaluate(
            IntrusionFlags(adbEnabled = true, developmentSettingsEnabled = false,
                unknownSourcesAllowed = false, mockLocationEnabled = false)
        )
        assertEquals(1, report.findings.size)
        assertEquals(Severity.HIGH, report.findings.first().severity)
        assertTrue(report.isCompromised)
        assertTrue(report.summary.contains("高危"))
    }

    @Test
    fun evaluateMediumOnlyIsNotCompromised() {
        val report = IntrusionDetector.evaluate(
            IntrusionFlags(adbEnabled = false, developmentSettingsEnabled = true,
                unknownSourcesAllowed = false, mockLocationEnabled = true)
        )
        assertEquals(2, report.findings.size)
        assertFalse(report.isCompromised)
        assertTrue(report.findings.all { it.severity == Severity.MEDIUM })
    }

    @Test
    fun evaluateSortsFindingsBySeverity() {
        val report = IntrusionDetector.evaluate(
            IntrusionFlags(adbEnabled = true, developmentSettingsEnabled = true,
                unknownSourcesAllowed = false, mockLocationEnabled = false)
        )
        // HIGH 应排在 MEDIUM 之前
        assertEquals(Severity.HIGH, report.findings.first().severity)
        assertEquals(Severity.MEDIUM, report.findings.last().severity)
    }
}
