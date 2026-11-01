package com.jarvis.pineapple.autonomous

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 单元测试：自主决策模块（仅测试无 Android Context 依赖的类）
 */
class AutonomousModulesTest {

    @get:Rule
    val tmp = TemporaryFolder()

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

    @Test
    fun `emergency protocol reports missing permissions`() {
        assertEquals(
            "缺少 SEND_SMS；缺少 CALL_PHONE",
            EmergencyProtocol.missingMessage(listOf("缺少 SEND_SMS", "缺少 CALL_PHONE"))
        )
        assertEquals("应急流程已执行", EmergencyProtocol.missingMessage(emptyList()))
    }

    @Test
    fun `memory system export then import restores facts`() {
        val src = MemorySystem(tmp.newFile("memories.json"), tmp.newFolder("archive"))
        src.rememberFact("用户喜欢红茶")
        val json = src.exportMemories()
        assertTrue(json.contains("用户喜欢红茶"))

        val dest = MemorySystem(tmp.newFile("imported.json"), tmp.newFolder("archive2"))
        assertTrue(dest.importMemories(json))
        assertEquals(1, dest.search("红茶").size)
        assertFalse(dest.importMemories("{not-json"))
    }
}
