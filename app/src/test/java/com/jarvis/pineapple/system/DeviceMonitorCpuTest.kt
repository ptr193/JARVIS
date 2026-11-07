package com.jarvis.pineapple.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMonitorCpuTest {

    @Test
    fun parseCpuSnapshotReadsIdleAndTotal() {
        val snap = DeviceMonitor.parseCpuSnapshot("cpu  10 0 10 80 0 0 0 0 0 0")
        assertEquals(80L, snap!!.idle)
        assertEquals(100L, snap.total)
    }

    @Test
    fun usagePercentUsesTwoSampleDelta() {
        val first = DeviceMonitor.parseCpuSnapshot("cpu  10 0 10 80 0 0 0")!!
        val second = DeviceMonitor.parseCpuSnapshot("cpu  20 0 20 160 0 0 0")!!
        val usage = DeviceMonitor.usagePercent(first, second)!!
        assertEquals(20f, usage, 0.01f)
    }

    @Test
    fun missingOrShortLineIsUnavailable() {
        assertNull(DeviceMonitor.parseCpuSnapshot(""))
        assertNull(DeviceMonitor.parseCpuSnapshot("intr 1 2 3"))
        assertNull(DeviceMonitor.parseCpuSnapshot("cpu  1 2"))
        assertTrue(DeviceMonitor.CPU_UNAVAILABLE.contains("无法读取 CPU 使用率"))
    }
}
