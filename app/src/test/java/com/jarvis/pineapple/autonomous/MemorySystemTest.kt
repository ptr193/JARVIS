package com.jarvis.pineapple.autonomous

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * MemorySystem 持久化单测。
 *
 * 重点验证：记忆的“已保存/已导入/已清空”不是假成功——必须真实落盘并能被新实例重新加载。
 * writeAtomically 在 renameTo 之后会校验文件存在且字节长度一致，这里通过重载验证其效果。
 */
class MemorySystemTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newSystem(dir: File): MemorySystem =
        MemorySystem(File(dir, "memory/memories.json"), File(dir, "memory/archive"))

    @Test
    fun rememberFactPersistsAndReloads() {
        val dir = tmp.newFolder("mem-remember")
        val system = newSystem(dir)
        assertTrue(system.rememberFact("用户住在北京", importance = 8))

        val memoryFile = File(dir, "memory/memories.json")
        assertTrue("记忆文件必须真实存在", memoryFile.exists())
        assertTrue("记忆文件必须非空", memoryFile.length() > 0)

        val reloaded = newSystem(dir)
        val facts = reloaded.getByType(MemoryType.KEY_FACT)
        assertEquals(1, facts.size)
        assertEquals("用户住在北京", facts.first().content)
        assertEquals(8, facts.first().importance)
    }

    @Test
    fun importMemoriesValidPersistsAndReloads() {
        val dir = tmp.newFolder("mem-import")
        val system = newSystem(dir)
        val json = """
            [{"id":"m1","type":"PREFERENCE","content":"深色主题","timestamp":100,"importance":6,"metadata":{}}]
        """.trimIndent()
        assertTrue(system.importMemories(json))

        val reloaded = newSystem(dir)
        val prefs = reloaded.getByType(MemoryType.PREFERENCE)
        assertEquals(1, prefs.size)
        assertEquals("深色主题", prefs.first().content)
    }

    @Test
    fun importMemoriesInvalidReturnsFalse() {
        val dir = tmp.newFolder("mem-bad")
        val system = newSystem(dir)
        assertFalse(system.importMemories("{not valid json"))
        assertEquals(0, system.memories.value.size)
    }

    @Test
    fun clearAllRemovesPersistedMemories() {
        val dir = tmp.newFolder("mem-clear")
        val system = newSystem(dir)
        system.rememberFact("临时事实")
        assertTrue(system.clearAll())
        assertEquals(0, system.memories.value.size)

        val reloaded = newSystem(dir)
        assertEquals(0, reloaded.memories.value.size)
    }
}
