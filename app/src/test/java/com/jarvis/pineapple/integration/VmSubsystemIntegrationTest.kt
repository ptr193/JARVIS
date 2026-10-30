package com.jarvis.pineapple.integration

import com.jarvis.pineapple.vm.ConfigValidationResult
import com.jarvis.pineapple.vm.VmConfigManager
import com.jarvis.pineapple.vm.VmManager
import com.jarvis.pineapple.vm.VmRootManager
import com.jarvis.pineapple.vm.VmState
import com.jarvis.pineapple.vm.VmTypeSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 集成测试：虚拟机子系统（REQ-01 / REQ-06 / REQ-07）。
 *
 * 串联「创建虚拟机 → 选择虚拟机类型 → 配置校验 → 应用配置 → 启动/停止 → Root 权限与审计」
 * 的完整流程，验证跨组件协作行为。
 */
class VmSubsystemIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `full vm lifecycle with haven type and root session`() {
        val manager = VmManager(tmp.root)
        val vm = manager.create("dev-box", "Debian 12")
        assertEquals(0, manager.vms.value.indexOfFirst { it.id == vm.id })

        // 1) 选择 Haven 桌面式虚拟机类型
        val selector = VmTypeSelector()
        selector.selectType(VmTypeSelector.VmType.HAVEN)
        assertEquals(VmTypeSelector.VmType.HAVEN, selector.getSelectedType())
        val info = selector.getTypeInfo(VmTypeSelector.VmType.HAVEN)
        assertTrue(info != null && info.isAvailable && info.features.isNotEmpty())

        // 2) 配置并应用（合法配置）
        val config = VmConfigManager(vm.id)
        config.setCpuCores(4)
        config.setMemory(4096)
        config.setGpuEnabled(true)
        config.setSharedFolderEnabled(true, "/sdcard/jarvis-shared")
        assertTrue("修改后应标记为已修改", config.isModified.value)
        assertTrue("合法配置应可应用", config.applyConfig())
        assertFalse("应用后不再是已修改状态", config.isModified.value)
        assertTrue(config.getConfigSummary().contains("CPU: 4 核"))

        // 3) 启动 / 停止
        manager.start(vm.id)
        assertEquals(VmState.RUNNING, manager.vms.value.first { it.id == vm.id }.state)
        manager.stop(vm.id)
        assertEquals(VmState.STOPPED, manager.vms.value.first { it.id == vm.id }.state)

        // 4) Root 权限：密码、切换、危险操作确认、降级与审计
        val root = VmRootManager(vm.id)
        assertTrue(root.setRootPassword("Sup3r-Secret"))
        assertTrue(root.verifyRootPassword("Sup3r-Secret"))
        assertFalse(root.verifyRootPassword("wrong"))
        assertTrue(root.switchToRoot())
        assertTrue(root.isCurrentUserRoot())
        assertTrue("rm 属于危险操作", root.needsConfirmation("rm -rf /data"))
        assertFalse("未确认的危险操作应被拒绝", root.executeDangerousAction("rm -rf /data", confirmed = false))
        assertTrue("已确认且具 Root 权限应执行", root.executeDangerousAction("rm -rf /data", confirmed = true))
        root.degradeToUserMode()
        assertFalse("降级后不应再具 Root 权限", root.canUseRoot())
        assertFalse(root.executeDangerousAction("rm -rf /", confirmed = true))
        assertTrue("审计日志应记录全部操作", root.getAuditLog().isNotEmpty())
    }

    @Test
    fun `invalid config is rejected before apply`() {
        val config = VmConfigManager("vm_test")
        // 启用共享文件夹但不给路径 → 非法
        config.setSharedFolderEnabled(true, "")
        val result = config.validate()
        assertTrue(result is ConfigValidationResult.Invalid)
        assertFalse("非法配置不应被应用", config.applyConfig())
    }

    @Test
    fun `oversized resources produce warnings but still apply`() {
        val config = VmConfigManager("vm_warn")
        config.setCpuCores(12)
        config.setMemory(32768)
        val result = config.validate()
        assertTrue(result is ConfigValidationResult.Warning)
        assertTrue("带告警的配置允许应用", config.applyConfig())
    }

    @Test
    fun `reset restores original then default`() {
        val config = VmConfigManager("vm_reset")
        config.setCpuCores(8)
        config.applyConfig()
        config.setCpuCores(2)
        config.resetToOriginal()
        assertEquals(8, config.getConfig().cpuCores)
        config.resetToDefault()
        assertEquals(2, config.getConfig().cpuCores)
        assertFalse(config.isModified.value)
    }
}
