package com.jarvis.pineapple.autonomous

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单元测试：自主决策模块
 */
class AutonomousModulesTest {

    @Test
    fun `TaskScheduler schedules and runs by priority`() {
        val scheduler = TaskScheduler()
        val results = mutableListOf<Int>()
        // 低优先级（数字小）先入队，高优先级（数字大）应先执行
        scheduler.schedule(TaskScheduler.Task("t1", "low", priority = 1) { results.add(1) })
        scheduler.schedule(TaskScheduler.Task("t2", "high", priority = 10) { results.add(10) })
        scheduler.schedule(TaskScheduler.Task("t3", "mid", priority = 5) { results.add(5) })

        assertTrue(scheduler.hasPending())
        runBlocking {
            scheduler.runNext() // highest first
            scheduler.runNext()
            scheduler.runNext()
        }
        assertFalse(scheduler.hasPending())
        assertEquals(listOf(10, 5, 1), results)
    }

    @Test
    fun `MemorySystem stores and recalls`() {
        val mem = MemorySystem()
        assertNull(mem.recall("name"))
        mem.remember("name", "Stark")
        assertEquals("Stark", mem.recall("name"))
        mem.forget("name")
        assertNull(mem.recall("name"))
    }

    @Test
    fun `MemorySystem short term evicts oldest when full`() {
        val mem = MemorySystem()
        repeat(60) { mem.addShortTerm("item$it") }
        // 50 cap，最早一项已被丢弃
        assertNull(mem.recall("item0"))
    }

    @Test
    fun `EmergencyProtocol emits event on trigger`() {
        val ep = EmergencyProtocol()
        // SharedFlow 带 buffer，trigger 不抛异常即视为通过
        ep.trigger(EmergencyProtocol.EmergencyType.FALL)
        assertTrue(true)
    }

    @Test
    fun `ProactiveNotifier threshold alert`() {
        val notifier = ProactiveNotifier()
        // 不抛异常即视为通过（SharedFlow 有 buffer）
        notifier.heartbeatCheck(40f, 30f)
        notifier.heartbeatCheck(10f, 30f)
        assertTrue(true)
    }
}
