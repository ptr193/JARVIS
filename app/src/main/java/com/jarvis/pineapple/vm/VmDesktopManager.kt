package com.jarvis.pineapple.vm

import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 虚拟机图形桌面管理：在 PRoot 内启动 Xvfb + XFCE + x11vnc，通过本机 RFB 端口暴露桌面。
 *
 * 用户可经内置 VNC 客户端操作桌面；AI 通过终端指令执行同一虚拟机内的命令，
 * 也可调用 [launchBrowser] 在桌面中打开浏览器。二者操作的是同一个 PRoot 会话与 rootfs。
 */
class VmDesktopManager(
    private val vmManager: HavenVmManager,
    private val prootManager: ProotManager
) {

    data class DesktopStatus(
        val running: Boolean,
        val port: Int = 0,
        val error: String? = null
    )

    private val ports = mutableMapOf<String, Int>()

    /** 安装 Xvfb/x11vnc/XFCE 等桌面组件。 */
    suspend fun installDesktop(vmId: String): Boolean = withContext(Dispatchers.IO) {
        vmManager.installDesktopEnvironment(vmId)
    }

    /** 启动桌面会话；缺少组件时返回明确错误，不伪造成功。 */
    suspend fun start(vmId: String, width: Int = 1024, height: Int = 768): DesktopStatus =
        withContext(Dispatchers.IO) {
            val vm = vmManager.findVm(vmId)
                ?: return@withContext DesktopStatus(false, error = "未找到虚拟机")
            if (vm.state != HavenVmManager.HavenVmState.RUNNING) {
                if (!vmManager.startVm(vmId)) {
                    return@withContext DesktopStatus(false, error = "虚拟机启动失败")
                }
            }
            val probe = vmManager.executeCommandResult(
                vmId,
                "command -v Xvfb >/dev/null 2>&1 && command -v x11vnc >/dev/null 2>&1"
            )
            if (probe == null || probe.exitCode != 0) {
                return@withContext DesktopStatus(
                    false,
                    error = "缺少 Xvfb/x11vnc，请先点击“安装桌面环境”（需联网）"
                )
            }

            val port = findFreePort()
            val display = port - 5900
            val command = buildString {
                append("Xvfb :$display -screen 0 ${width}x${height}x24 -nolisten tcp ")
                append(">/tmp/xvfb-$display.log 2>&1 & ")
                append("sleep 1; export DISPLAY=:$display; ")
                append(
                    "(command -v startxfce4 >/dev/null 2>&1 && " +
                        "(command -v dbus-launch >/dev/null 2>&1 && " +
                        "dbus-launch --exit-with-session startxfce4 || startxfce4) || xterm) " +
                        ">/tmp/desktop-$display.log 2>&1 & "
                )
                append("exec x11vnc -display :$display -forever -shared -nopw -rfbport $port -localhost -quiet")
            }
            val ok = vmManager.launchBackground(vmId, key(vmId), command)
            if (!ok) {
                DesktopStatus(false, error = "桌面进程启动失败，请查看 /tmp/xvfb-$display.log")
            } else {
                ports[vmId] = port
                DesktopStatus(true, port)
            }
        }

    suspend fun stop(vmId: String): Boolean = withContext(Dispatchers.IO) {
        ports.remove(vmId)
        vmManager.stopDesktop(vmId)
    }

    fun isRunning(vmId: String): Boolean = prootManager.isBackgroundRunning(key(vmId))

    fun portFor(vmId: String): Int = ports[vmId] ?: 5901

    /**
     * 在桌面中启动浏览器（保持进程常驻，供用户或 AI 使用）。
     * 未安装任何浏览器时返回 false，由调用方提示安装。
     */
    suspend fun launchBrowser(vmId: String): Boolean = withContext(Dispatchers.IO) {
        val vm = vmManager.findVm(vmId) ?: return@withContext false
        val port = ports[vmId] ?: return@withContext false
        val display = port - 5900
        val command = buildString {
            append("export DISPLAY=:$display; ")
            append("for b in firefox-esr firefox chromium chromium-browser google-chrome; do ")
            append("command -v \$b >/dev/null 2>&1 && exec \$b; done; ")
            append("echo 'no browser installed' >&2; exit 127")
        }
        vmManager.launchBackground(vmId, "${key(vmId)}-browser", command)
    }

    private fun key(vmId: String): String = "desktop-$vmId"

    private fun findFreePort(): Int {
        for (port in 5901..5910) {
            try {
                ServerSocket().use {
                    it.reuseAddress = false
                    it.bind(InetSocketAddress("127.0.0.1", port))
                }
                return port
            } catch (_: Exception) {
                // 端口被占用，尝试下一个
            }
        }
        return 5901
    }
}
