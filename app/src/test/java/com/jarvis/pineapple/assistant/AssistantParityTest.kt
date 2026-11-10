package com.jarvis.pineapple.assistant

import com.jarvis.pineapple.perception.CameraCaptureSession
import java.io.File
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantParityTest {

    @Test
    fun overlayPromptIncludesScreenContext() {
        assertEquals("你好", OverlayReplyCoordinator.buildPrompt("你好", ""))
        assertEquals(
            "当前屏幕内容：设置页\n用户说：打开蓝牙",
            OverlayReplyCoordinator.buildPrompt("打开蓝牙", "设置页")
        )
        assertEquals("", OverlayReplyCoordinator.buildPrompt("  ", "屏幕"))
        assertTrue(OverlayReplyCoordinator.shouldSpeak("可以"))
        assertFalse(OverlayReplyCoordinator.shouldSpeak(" "))
    }

    @Test
    fun overlayReplyCollectsFlowOrChineseFailure() = runBlocking {
        assertEquals("答", OverlayReplyCoordinator.collectReply(flowOf("答")))
        assertEquals(OverlayReplyCoordinator.EMPTY_REPLY, OverlayReplyCoordinator.collectReply(flowOf("")))
        val failed = OverlayReplyCoordinator.collectReply(flow { error("boom") })
        assertTrue(failed.startsWith("回答失败："))
    }

    @Test
    fun assistStructureReaderNeverFakesScreenText() {
        assertEquals("标题 按钮", AssistStructureReader.collectTexts(sequenceOf(" 标题 ", "", "按钮")))
        assertEquals(AssistStructureReader.EMPTY_SCREEN, AssistStructureReader.messageFor("  "))
        assertEquals("相册", AssistStructureReader.messageFor("相册"))
    }

    @Test
    fun suggestionCardsSkipEmptyAndDuplicateFingerprints() {
        assertFalse(SuggestionCardPublisher.shouldPublish(null, "k", 0))
        assertTrue(SuggestionCardPublisher.shouldPublish(null, "k", 1))
        assertFalse(SuggestionCardPublisher.shouldPublish("k", "k", 2))
        assertEquals("2026-11-10:a|b", SuggestionCardPublisher.fingerprint(listOf("a", "b"), "2026-11-10"))
    }

    @Test
    fun overlayCameraRoutesObjectAndDocumentWithoutFakingSuccess() {
        assertEquals(CameraCaptureSession.Task.OBJECT, OverlayCameraController.resolveTask(null))
        assertEquals(
            CameraCaptureSession.Task.DOCUMENT,
            OverlayCameraController.resolveTask(OverlayCameraController.TASK_DOCUMENT)
        )
        assertEquals("扫文档", OverlayCameraController.titleFor(CameraCaptureSession.Task.DOCUMENT))
        assertEquals("识物", OverlayCameraController.titleFor(CameraCaptureSession.Task.OBJECT))
        assertEquals(CameraCaptureSession.NO_IMAGE, OverlayCameraController.noImageMessage())
    }

    @Test
    fun overlaySessionAnswersScreenAndCameraWithoutLaunchingMainUi() {
        val src = readSource("app/src/main/java/com/jarvis/pineapple/assistant/JarvisVoiceInteractionSessionService.kt")
        assertTrue(src.contains("ChatEngine"))
        assertTrue(src.contains("SpeechManager"))
        assertTrue(src.contains("onHandleAssist"))
        assertTrue(src.contains("OverlayCameraActivity"))
        assertTrue(!src.contains("startVoiceActivity"))
        assertTrue(!src.contains("MainActivity"))
    }

    @Test
    fun keepAlivePublishesSuggestionCards() {
        val src = readSource("app/src/main/java/com/jarvis/pineapple/service/KeepAliveService.kt")
        assertTrue(src.contains("generateContextualSuggestions"))
        assertTrue(src.contains("SuggestionCardPublisher.publish"))
    }

    @Test
    fun generalTabDetectsDefaultAssistant() {
        val src = readSource("app/src/main/java/com/jarvis/pineapple/ui/settings/SettingsScreen.kt")
        assertTrue(src.contains("isDefaultAssistant"))
        assertTrue(src.contains("尚未设为默认数字助手"))
        assertTrue(src.contains("JARVIS 已是默认数字助手"))
        assertTrue(src.contains("VOICE_INPUT_SETTINGS"))
    }

    private fun readSource(relativePath: String): String {
        val candidates = listOf(File(relativePath), File("../$relativePath"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("无法定位源文件: $relativePath")
        return file.readText()
    }
}
