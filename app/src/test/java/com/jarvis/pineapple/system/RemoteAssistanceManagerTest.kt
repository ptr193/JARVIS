package com.jarvis.pineapple.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * RemoteAssistanceManager 纯逻辑单测。
 * 注意：MediaProjection / FileProvider 相关方法依赖真实 Android 运行时，无法在 JVM 单测中验证硬件效果；
 * 这里只覆盖可脱离框架的纯判断逻辑 isShareable。
 */
class RemoteAssistanceManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun isShareableRejectsNull() {
        assertFalse(RemoteAssistanceManager.isShareable(null))
    }

    @Test
    fun isShareableRejectsNonExistent() {
        assertFalse(RemoteAssistanceManager.isShareable(File(temp.root, "missing.png")))
    }

    @Test
    fun isShareableRejectsEmptyFile() {
        val file = temp.newFile("empty.png")
        assertFalse(RemoteAssistanceManager.isShareable(file))
    }

    @Test
    fun isShareableAcceptsNonEmptyFile() {
        val file = temp.newFile("shot.png")
        file.writeText("png-bytes")
        assertTrue(RemoteAssistanceManager.isShareable(file))
    }
}
