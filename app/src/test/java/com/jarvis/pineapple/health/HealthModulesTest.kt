package com.jarvis.pineapple.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单元测试：健康层模块
 */
class HealthModulesTest {

    @Test
    fun `FatigueMonitor detects low eye aspect ratio`() {
        val m = FatigueMonitor()
        // 单帧闭合 → PERCLOS=1.0 > 阈值 → 疲劳
        assertTrue(m.detectFatigue(eyeAspectRatio = 0.15f, yawnCount = 0))
        // 连续多帧正常眼开度 → PERCLOS 下降至阈值以下 → 不疲劳
        repeat(5) { m.detectFatigue(eyeAspectRatio = 0.40f, yawnCount = 0) }
        assertFalse(m.detectFatigue(eyeAspectRatio = 0.40f, yawnCount = 0))
    }

    @Test
    fun `FatigueMonitor detects excessive yawns`() {
        val m = FatigueMonitor()
        assertTrue(m.detectFatigue(eyeAspectRatio = 0.40f, yawnCount = 3))
        assertFalse(m.detectFatigue(eyeAspectRatio = 0.40f, yawnCount = 2))
    }

    @Test
    fun `HealthManager tracks medications`() {
        val now = System.currentTimeMillis()
        val m = HealthManager()
        m.addMedication(HealthManager.Medication("阿司匹林", "100mg", now - 1000))
        m.addMedication(HealthManager.Medication("维生素", "1粒", now + 60000))
        val due = m.getDueMedications(now)
        assertEquals(1, due.size)
        assertEquals("阿司匹林", due.first().name)
    }

    @Test
    fun `addMedication schedules reminder via AlarmManager path`() {
        val scheduled = mutableListOf<String>()
        val now = 1_700_000_000_000L
        val m = HealthManager { id, title, description, triggerTime ->
            scheduled.add("$id|$title|$description|$triggerTime")
        }
        m.addMedication(HealthManager.Medication("阿司匹林", "100mg", now))
        assertEquals(1, scheduled.size)
        assertTrue(scheduled.first().contains("用药提醒：阿司匹林"))
        assertTrue(scheduled.first().contains("剂量 100mg"))
        assertTrue(scheduled.first().endsWith(now.toString()))
    }

    @Test
    fun `HealthManager analyzeTrend returns string`() {
        val m = HealthManager()
        val result = m.analyzeTrend(emptyList())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `VitalSignsMonitor default vital signs`() {
        val v = VitalSignsMonitor.VitalSigns()
        assertEquals(0, v.heartRate)
        assertEquals("", v.source)
    }
}
