package com.jarvis.pineapple.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 危险命令识别与二次确认口令（纯函数）测试。
 * 覆盖 REQ-06「危险 root 操作执行前二次确认」的判定逻辑。
 */
class DangerousCommandTest {

    @Test
    fun detectsDestructiveCommands() {
        assertTrue(ChatEngine.isDangerousCommand("rm -rf /"))
        assertTrue(ChatEngine.isDangerousCommand("rm -rf /data"))
        assertTrue(ChatEngine.isDangerousCommand("mkfs.ext4 /dev/block/sda"))
        assertTrue(ChatEngine.isDangerousCommand("dd if=/dev/zero of=/dev/block/sda"))
        assertTrue(ChatEngine.isDangerousCommand("reboot"))
        assertTrue(ChatEngine.isDangerousCommand("shutdown -h now"))
        assertTrue(ChatEngine.isDangerousCommand("iptables -F"))
        assertTrue(ChatEngine.isDangerousCommand("userdel test"))
    }

    @Test
    fun allowsOrdinaryCommands() {
        assertFalse(ChatEngine.isDangerousCommand("apt-get install nginx"))
        assertFalse(ChatEngine.isDangerousCommand("ls -la /"))
        assertFalse(ChatEngine.isDangerousCommand("cat /data/config"))
        assertFalse(ChatEngine.isDangerousCommand("echo hello"))
        assertFalse(ChatEngine.isDangerousCommand("id"))
        assertFalse(ChatEngine.isDangerousCommand(""))
    }

    @Test
    fun confirmationAndCancelPhrases() {
        assertTrue(ChatEngine.isConfirmationText("确认执行"))
        assertTrue(ChatEngine.isConfirmationText("继续"))
        assertTrue(ChatEngine.isConfirmationText("yes"))
        assertFalse(ChatEngine.isConfirmationText("取消"))

        assertTrue(ChatEngine.isCancelText("取消"))
        assertTrue(ChatEngine.isCancelText("不要"))
        assertTrue(ChatEngine.isCancelText("no"))
        assertFalse(ChatEngine.isCancelText("确认执行"))
    }
}
