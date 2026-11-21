package com.jarvis.pineapple.plugin

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun stagedFileReplacesExistingPluginOnlyWhenMoveSucceeds() {
        val target = temp.newFile("plugin.apk").apply { writeText("old") }
        val staged = temp.newFile("staged.apk").apply { writeText("new") }

        PluginManager.replaceStagedFile(staged, target)

        assertEquals("new", target.readText())
        assertFalse(staged.exists())
    }

    @Test
    fun failedReplacementPreservesBothOldTargetAndStaging() {
        val target = temp.newFolder("plugin.apk")
        val oldContent = File(target, "old.txt").apply { writeText("old") }
        val staged = temp.newFile("staged.apk").apply { writeText("new") }

        assertThrows(IOException::class.java) {
            PluginManager.replaceStagedFile(staged, target)
        }

        assertEquals("old", oldContent.readText())
        assertEquals("new", staged.readText())
    }

    @Test
    fun installValidatesStagingBeforeTouchingTarget() {
        val source = readSource("app/src/main/java/com/jarvis/pineapple/plugin/PluginManager.kt")
        val install = source.substringAfter("fun install(source: File)")
            .substringBefore("/** 卸载插件。 */")

        assertTrue(install.contains("loader.loadFrom(staged)"))
        assertTrue(install.contains("replaceStagedFile(staged, target)"))
        assertTrue(
            install.indexOf("loader.loadFrom(staged)") <
                install.indexOf("replaceStagedFile(staged, target)")
        )
        assertFalse("安装失败不得删除已有目标插件", install.contains("target.delete()"))
        assertTrue(install.contains("finally"))
    }

    @Test
    fun disableFailsWhenPluginUnloadFails() {
        val source = readSource("app/src/main/java/com/jarvis/pineapple/plugin/PluginManager.kt")
        val disable = source.substringAfter("fun disable(pluginId: String)")
            .substringBefore("/** 执行插件命令")
        val deactivate = source.substringAfter("private fun deactivate(")
            .substringBefore("private fun activate(")

        assertTrue(disable.contains("if (!deactivate(loaded)) return false"))
        assertFalse(disable.contains("runCatching"))
        assertTrue(deactivate.contains("loaded.instance.onUnload()"))
        assertTrue(deactivate.contains("catch (e: Throwable)"))
        assertTrue(deactivate.contains("false"))
        assertTrue(
            deactivate.indexOf("loaded.instance.onUnload()") <
                deactivate.indexOf("loaded.enabled = false")
        )
    }

    @Test
    fun uninstallDeactivatesBeforeDeletionAndRestoresOnDeleteFailure() {
        val source = readSource("app/src/main/java/com/jarvis/pineapple/plugin/PluginManager.kt")
        val uninstall = source.substringAfter("fun uninstall(pluginId: String)")
            .substringBefore("/** 启用插件。")
        val deleteFailure = uninstall.substringAfter("if (!deleted)")

        assertTrue(uninstall.contains("if (!deactivate(loaded)) return false"))
        assertTrue(uninstall.indexOf("deactivate(loaded)") < uninstall.indexOf("file.delete()"))
        assertTrue(deleteFailure.contains("loaded.enabled = activate(loaded)"))
        assertTrue(deleteFailure.contains("return false"))
        assertTrue(uninstall.indexOf("if (!deleted)") < uninstall.indexOf("instances.remove(pluginId)"))
    }

    private fun readSource(relativePath: String): String {
        val candidates = listOf(File(relativePath), File("../$relativePath"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("无法定位源文件: $relativePath")
    }
}
