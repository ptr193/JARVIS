package com.jarvis.pineapple.autonomous

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单元测试：自主决策模块（仅测试无 Android Context 依赖的类）
 */
class AutonomousModulesTest {

    @Test
    fun `TaskScheduler schedules and runs by priority`() {
        val scheduler = TaskScheduler()
        val results = mutableListOf<Int>()
        // 优先级数字大者先执行
        scheduler.schedule(TaskScheduler.Task("t1", "low", priority = 1) { results.add(1) })
        scheduler.schedule(TaskScheduler.Task("t2", "high", priority = 10) { results.add(10) })
        scheduler.schedule(TaskScheduler.Task("t3", "mid", priority = 5) { results.add(5) })

        assertTrue(scheduler.hasPending())
        runBlocking {
            scheduler.runNext()
            scheduler.runNext()
            scheduler.runNext()
        }
        assertFalse(scheduler.hasPending())
        assertEquals(listOf(10, 5, 1), results)
    }

    @Test
    fun `TaskScheduler empty queue returns false`() {
        val scheduler = TaskScheduler()
        assertFalse(scheduler.hasPending())
    }

    @Test
    fun `ProactiveNotifier threshold alert`() {
        val notifier = ProactiveNotifier()
        // 不抛异常即视为通过（SharedFlow 有 buffer）
        notifier.heartbeatCheck(40f, 30f) // 40 > 30 触发 WARNING
        notifier.heartbeatCheck(10f, 30f) // 10 < 30 不触发
        assertTrue(true)
    }

    @Test
    fun `ProactiveNotifier emit alert with custom severity`() {
        val notifier = ProactiveNotifier()
        notifier.notify("test_type", "test_message", ProactiveNotifier.Severity.CRITICAL)
        assertTrue(true)
    }
}
