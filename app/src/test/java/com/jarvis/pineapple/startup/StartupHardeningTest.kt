package com.jarvis.pineapple.startup

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动路径健壮性回归测试（BUG-2026-1029）。
 *
 * 背景：第三轮实机测试中，应用「短暂显示主界面后立即闪退，之后再也打不开」，
 * 但模拟器（x86_64）完全不复现。由于本环境无法连接真机、用户也拿不到 logcat，
 * 采取两条可静态验证的加固措施，并用测试锁死，防止回退：
 *
 *  1. 全局未捕获异常处理器：把崩溃堆栈持久化到 filesDir/crash/，供应用内导出，
 *     不依赖 USB 调试即可获得真机第一手证据。
 *  2. 首帧路径上不得出现「可能在真机失败的重活」：
 *     - ONNX 原生库（OrtEnvironment.getEnvironment）不得在构造期加载；
 *     - SQLite 数据库（DatabaseFactory.create）不得在构造期打开；
 *     - TextToSpeech 初始化必须异常隔离。
 *
 * 这些断言基于源码文本，属静态校验（无法在纯 JVM 测试中驱动 Android 生命周期）。
 */
class StartupHardeningTest {

    private fun readSource(relativePath: String): String {
        val candidates = listOf(File(relativePath), File("../$relativePath"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("无法定位源文件: $relativePath (cwd=${File(".").absolutePath})")
        return file.readText()
    }

    private fun extractMethod(src: String, name: String): String {
        val header = Regex("""fun\s+$name\s*\(""").find(src)
            ?: error("未找到方法 $name")
        val braceStart = src.indexOf('{', header.range.last)
        if (braceStart < 0) return src.substring(header.range.first)
        var depth = 0
        var i = braceStart
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(header.range.first, i + 1)
                }
            }
            i++
        }
        return src.substring(header.range.first)
    }

    // ==================== 全局崩溃记录 ====================

    @Test
    fun applicationInstallsGlobalUncaughtExceptionHandler() {
        val appSrc = readSource("app/src/main/java/com/jarvis/pineapple/JarvisApp.kt")
        assertTrue(
            "JarvisApp 必须安装全局未捕获异常处理器（用于记录真机崩溃）",
            appSrc.contains("Thread.setDefaultUncaughtExceptionHandler")
        )
        assertTrue(
            "崩溃必须被写入 filesDir 下的文件（CrashRecorder）",
            appSrc.contains("CrashRecorder")
        )
        // 必须链回上一个处理器，否则进程不会正常终止（表现为卡死而非闪退）
        val install = extractMethod(appSrc, "installCrashRecorder")
        assertTrue(
            "崩溃记录后必须调用 previous?.uncaughtException，保证进程正常终止",
            install.contains("previous") && install.contains("uncaughtException")
        )
    }

    @Test
    fun crashRecorderPersistsToFilesDir() {
        val recorder = readSource("app/src/main/java/com/jarvis/pineapple/util/CrashRecorder.kt")
        assertTrue(
            "CrashRecorder 必须写入 filesDir（无需存储权限即可导出）",
            recorder.contains("filesDir")
        )
        // 记录器自身绝不能抛异常，否则会掩盖原始崩溃
        assertTrue(
            "CrashRecorder.record 必须内部捕获所有异常",
            recorder.contains("catch (e: Throwable)")
        )
    }

    // ==================== 首帧路径不得做重活 ====================

    @Test
    fun onnxNativeLibraryNotLoadedAtConstruction() {
        val voiceSrc = readSource(
            "app/src/main/java/com/jarvis/pineapple/speech/VoiceManager.kt"
        )
        // OrtEnvironment.getEnvironment() 会立即加载 libonnxruntime.so，
        // 不得作为字段初始化器（构造即执行）
        assertTrue(
            "VoiceManager 不得在字段初始化时调用 OrtEnvironment.getEnvironment()",
            !Regex("""val\s+ortEnvironment\s*(:[^=]*)?=\s*OrtEnvironment\.getEnvironment\(""")
                .containsMatchIn(voiceSrc)
        )
        assertTrue(
            "VoiceManager 的 ONNX 环境必须惰性初始化（by lazy）",
            voiceSrc.contains("by lazy") && voiceSrc.contains("OrtEnvironment.getEnvironment()")
        )
    }

    @Test
    fun databaseNotOpenedAtConstruction() {
        val engineSrc = readSource(
            "app/src/main/java/com/jarvis/pineapple/info/DatabaseQueryEngine.kt"
        )
        // DatabaseFactory.create 会打开 jarvis.db 并建表/迁移，不得在构造期执行
        assertTrue(
            "DatabaseQueryEngine 不得在构造参数默认值中调用 DatabaseFactory.create()",
            !Regex("""DatabaseQueryEngine\s*\([^)]*DatabaseFactory\.create\(""")
                .containsMatchIn(engineSrc)
        )
        assertTrue(
            "DatabaseQueryEngine 的数据库必须惰性打开（by lazy）",
            engineSrc.contains("DatabaseFactory.create(context)") && engineSrc.contains("by lazy")
        )
    }

    @Test
    fun infoProcessorDoesNotEagerlyOpenDatabase() {
        val infoSrc = readSource("app/src/main/java/com/jarvis/pineapple/info/InfoProcessor.kt")
        assertTrue(
            "InfoProcessor 构造 DatabaseQueryEngine 时必须仍为惰性（不得 eager get）",
            infoSrc.contains("DatabaseQueryEngine(")
        )
    }

    @Test
    fun ttsInitializationIsIsolated() {
        val speechSrc = readSource(
            "app/src/main/java/com/jarvis/pineapple/speech/SpeechManager.kt"
        )
        val init = extractMethod(speechSrc, "initializeTts")
        assertTrue(
            "initializeTts 必须用 runCatching 隔离异常，避免真机无 TTS 引擎时闪退",
            init.contains("runCatching")
        )
    }

    @Test
    fun appModuleDoesNotForceEagerDatabaseIntoQueryEngine() {
        val moduleSrc = readSource(
            "app/src/main/java/com/jarvis/pineapple/di/AppModule.kt"
        )
        // DatabaseQueryEngine 只应取 Context，不应再注入已创建的数据库（那会绕过惰性打开）
        assertTrue(
            "AppModule 中 DatabaseQueryEngine 只应注入 Context（get()）",
            moduleSrc.contains("single { DatabaseQueryEngine(get()) }")
        )
    }

    @Test
    fun modelSchedulerBindsUserLocalModelNotAiModelInterface() {
        val moduleSrc = readSource(
            "app/src/main/java/com/jarvis/pineapple/di/AppModule.kt"
        )
        assertTrue(
            "UserLocalModel 必须按具体类型注册",
            moduleSrc.contains("single { UserLocalModel(get()) }")
        )
        assertTrue(
            "ModelScheduler.userModel 必须 get<UserLocalModel>()，禁止无类型 get() 解析 AiModel 接口（BUG-2026-1103）",
            moduleSrc.contains("ModelScheduler(userModel = get<UserLocalModel>()")
        )
        assertTrue(
            "AppModule 不得把 AiModel 接口注册成唯一 bean 定义来源",
            !moduleSrc.contains("single<AiModel>")
        )
    }
}
