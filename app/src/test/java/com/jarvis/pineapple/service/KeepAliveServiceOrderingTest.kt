package com.jarvis.pineapple.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前台服务启动时序回归测试（BUG-2026-1025）。
 *
 * 背景：实机/模拟器日志显示应用启动即崩溃：
 *   RemoteServiceException: Context.startForegroundService() did not then call
 *   Service.startForeground(): ServiceRecord{... KeepAliveService}
 *
 * 成因：系统要求调用 startForegroundService() 后约 5 秒内必须调用
 * startForeground()。原实现把该调用写在 onCreate 里、且排在可能耗时的初始化之后，
 * 又在 Application.onCreate（应用尚未进入前台）中启动服务，导致主线程繁忙时超时崩溃。
 *
 * 本测试对这些**时序不变量**做静态校验：一旦有人把顺序改回去，测试立刻失败。
 * （Service 生命周期无法在纯 JVM 单元测试中直接驱动，故采用源码级断言。）
 */
class KeepAliveServiceOrderingTest {

    private val serviceSrc: String = readSource(
        "app/src/main/java/com/jarvis/pineapple/service/KeepAliveService.kt"
    )
    private val appSrc: String = readSource(
        "app/src/main/java/com/jarvis/pineapple/JarvisApp.kt"
    )
    private val activitySrc: String = readSource(
        "app/src/main/java/com/jarvis/pineapple/ui/MainActivity.kt"
    )

    @Test
    fun startForegroundBeforeWakeEngine() {
        val onCreate = extractMethod(serviceSrc, "onCreate")

        val idxForeground = onCreate.indexOf("startForeground(")
        assertTrue("onCreate 中必须调用 startForeground()", idxForeground >= 0)

        // 唤醒引擎启动属于耗时/依赖其它组件的操作，必须排在 startForeground 之后
        val idxWake = onCreate.indexOf("WakeEngine")
        if (idxWake >= 0) {
            assertTrue("startForeground() 必须早于 WakeEngine 初始化，否则会超时崩溃", idxForeground < idxWake)
        }
    }

    @Test
    fun startForegroundBeforeHeavyWork() {
        val onCreate = extractMethod(serviceSrc, "onCreate")

        // 允许 createChannel（轻量 binder 调用）在 startForeground 之前，
        // 但不允许出现其它可能阻塞的调用先于 startForeground。
        val idxForeground = onCreate.indexOf("startForeground(")
        val blockedMarkers = listOf("GlobalContext.get()", ".get<", "WakeEngine")
        for (marker in blockedMarkers) {
            val idx = onCreate.indexOf(marker)
            if (idx >= 0) {
                assertTrue("耗时调用 `$marker` 不得早于 startForeground()", idxForeground < idx)
            }
        }
    }

    @Test
    fun applicationMustNotStartForegroundService() {
        // Application.onCreate 时应用尚未进入前台，启动 FGS 在 Android 12+ 会被拒绝
        assertTrue(
            "JarvisApp 不应启动 KeepAliveService（应改由 MainActivity.onStart 启动）",
            !appSrc.contains("KeepAliveService.start")
        )
    }

    @Test
    fun serviceStartDeferredUntilAfterFirstFrame() {
        val onCreate = extractMethod(activitySrc, "onCreate")
        // 服务启动必须发生在首帧之后（decorView.post），不能在 onCreate 同步启动
        assertTrue(
            "MainActivity 必须在首帧后再启动 KeepAliveService（decorView.post）",
            onCreate.contains("decorView.post") && onCreate.contains("KeepAliveService.startSafely")
        )
        // 不得有同步的 onStart 中启动（旧方案已废弃）
        assertTrue(
            "MainActivity 不应再在 onStart 中启动前台服务",
            !activitySrc.contains("override fun onStart()")
        )
    }

    @Test
    fun autonomousLoopDeferredFromStartup() {
        // 自主循环首轮必须延后，避免在冷启动窗口内触发重类的加载与校验
        val onStartCommand = extractMethod(serviceSrc, "onStartCommand")
        val loopIdx = serviceSrc.indexOf("fun startAutonomousLoop")
        assertTrue("未找到 startAutonomousLoop", loopIdx >= 0)
        val loopBody = serviceSrc.substring(loopIdx)
        assertTrue(
            "自主循环必须在首轮前 delay，避开冷启动窗口",
            loopBody.contains("AUTONOMOUS_INITIAL_DELAY_MS")
        )
        assertTrue(
            "onStartCommand 应启动自主循环",
            onStartCommand.contains("startAutonomousLoop()")
        )
    }

    @Test
    fun wakeEngineStartRunsOffMainThread() {
        // 唤醒引擎初始化必须不在主线程调度器上，否则会阻塞首帧并触发前台服务超时
        val wakeSrc = readSource("app/src/main/java/com/jarvis/pineapple/wake/WakeEngine.kt")
        assertTrue(
            "WakeEngine 不得使用 Dispatchers.Main 作为初始化作用域",
            !wakeSrc.contains("SupervisorJob() + Dispatchers.Main")
        )
    }

    @Test
    fun missingMicPermissionSurfacesChineseReasonWithoutBlockingOtherSources() {
        val voiceSrc = readSource("app/src/main/java/com/jarvis/pineapple/wake/VoiceWakeDetector.kt")
        assertTrue(
            "缺麦克风权限必须给出中文原因",
            voiceSrc.contains("未授予麦克风权限，语音唤醒不可用")
        )
        assertTrue(
            "缺权限时其他唤醒方式仍应可用",
            voiceSrc.contains("其他唤醒方式仍可使用")
        )
        val wakeSrc = readSource("app/src/main/java/com/jarvis/pineapple/wake/WakeEngine.kt")
        assertTrue(
            "WakeEngine 必须汇总不可用原因供 UI 展示",
            wakeSrc.contains("unavailableReasons")
        )
        val chatSrc = readSource("app/src/main/java/com/jarvis/pineapple/ui/chat/ChatScreen.kt")
        assertTrue(
            "聊天页必须展示唤醒不可用原因",
            chatSrc.contains("unavailableReasons")
        )
    }

    @Test
    fun defaultAssistantSessionNotifiesHomeLongPress() {
        val sessionSrc = readSource(
            "app/src/main/java/com/jarvis/pineapple/assistant/JarvisVoiceInteractionSessionService.kt"
        )
        assertTrue(
            "默认助手会话必须通知 Home 长按检测器",
            sessionSrc.contains("HomeLongPressDetector.notifyHomeLongPress")
        )
        assertTrue(
            "长按 Home 必须展示声纹 overlay",
            sessionSrc.contains("onCreateContentView") && sessionSrc.contains("voice_assist_overlay")
        )
        assertTrue(
            "长按 Home 不得拉起主界面",
            !sessionSrc.contains("startVoiceActivity")
        )
        val detectorSrc = readSource("app/src/main/java/com/jarvis/pineapple/wake/HomeLongPressDetector.kt")
        assertTrue(
            "HomeLongPressDetector 必须暴露供 VoiceInteractionService 调用的入口",
            detectorSrc.contains("fun notifyHomeLongPress")
        )
    }

    // ==================== 工具 ====================

    private fun readSource(relativePath: String): String {
        val candidates = listOf(File(relativePath), File("../$relativePath"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("无法定位源文件: $relativePath (cwd=${File(".").absolutePath})")
        return file.readText()
    }

    /** 粗略提取以 `fun name(` 开始的方法体（按花括号配平）。找不到即抛错。 */
    private fun extractMethod(src: String, name: String): String {
        val header = Regex("""fun\s+$name\s*\(""").find(src)
            ?: error("未找到方法 $name")
        val open = src.indexOf('{', header.range.last)
        require(open >= 0) { "方法 $name 无函数体" }
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
        }
        return src.substring(open)
    }
}
