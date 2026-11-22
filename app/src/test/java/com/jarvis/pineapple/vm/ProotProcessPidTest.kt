package com.jarvis.pineapple.vm

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进程 PID 反射读取测试（真实 JVM 进程，不触碰 Android 框架）。
 * Android 上字段名可能不同，读取失败时实现会如实返回 null 并令暂停失败。
 */
class ProotProcessPidTest {

    @Test
    fun readsPidFromRealProcess() {
        val process = ProcessBuilder("sleep", "2").start()
        try {
            val pid = ProotManager.processPid(process)
            assertNotNull("JVM ProcessImpl 应能通过 pid 字段读取 PID", pid)
            assertTrue((pid ?: 0) > 0)
        } finally {
            process.destroy()
        }
    }
}
