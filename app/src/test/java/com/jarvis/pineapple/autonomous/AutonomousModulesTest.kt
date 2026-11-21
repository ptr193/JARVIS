package com.jarvis.pineapple.autonomous

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
    fun `ProactiveNotifier threshold alert`() = runBlocking {
        val notifier = ProactiveNotifier()
        var received: ProactiveNotifier.Alert? = null
        val collector = launch { received = notifier.alerts.first() }
        yield()

        notifier.heartbeatCheck(40f, 30f)
        notifier.heartbeatCheck(10f, 30f)
        collector.join()

        assertEquals("threshold", received?.type)
        assertEquals(ProactiveNotifier.Severity.WARNING, received?.severity)
        assertTrue(received?.message?.contains("40") == true)
    }

    @Test
    fun `ProactiveNotifier emit alert with custom severity`() = runBlocking {
        val notifier = ProactiveNotifier()
        var received: ProactiveNotifier.Alert? = null
        val collector = launch { received = notifier.alerts.first() }
        yield()

        notifier.notify("test_type", "test_message", ProactiveNotifier.Severity.CRITICAL)
        collector.join()

        assertEquals(
            ProactiveNotifier.Alert("test_type", "test_message", ProactiveNotifier.Severity.CRITICAL),
            received
        )
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
        assertEquals(1, dest.search("红茶").size)
        assertTrue(dest.lastError.value?.contains("导入失败") == true)
    }

    @Test
    fun `memory save failure is visible and does not change state`() {
        val blockedParent = tmp.newFile("not-a-directory")
        val memory = MemorySystem(
            File(blockedParent, "memories.json"),
            File(tmp.root, "unused-archive")
        )

        assertFalse(memory.rememberFact("不能丢失的事实"))
        assertEquals(0, memory.getStats().totalCount)
        assertTrue(memory.lastError.value?.contains("保存失败") == true)
    }

    @Test
    fun `memory archive failure retains all current entries`() {
        val dir = tmp.newFolder("archive-blocked")
        val blockedArchive = tmp.newFile("archive-is-a-file")
        val memory = MemorySystem(File(dir, "memories.json"), File(blockedArchive, "archive"))

        repeat(1000) { index ->
            assertTrue(memory.recordConversation("记忆-$index"))
        }
        assertFalse(memory.recordConversation("触发归档"))

        assertEquals(1000, memory.getStats().totalCount)
        assertTrue(memory.lastError.value?.contains("归档失败") == true)
    }
}
