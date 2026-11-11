package com.jarvis.pineapple.assistant

import com.jarvis.pineapple.perception.CameraCaptureSession
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadStressTest {

    @Test
    fun overlayPromptSurvivesLongScreenContextAndEmptyUser() {
        val longScreen = "屏幕" + "字".repeat(4000)
        val prompt = OverlayReplyCoordinator.buildPrompt("打开蓝牙", longScreen)
        assertTrue(prompt.startsWith("当前屏幕内容："))
        assertTrue(prompt.endsWith("用户说：打开蓝牙"))
        assertEquals("", OverlayReplyCoordinator.buildPrompt("", longScreen))
        assertEquals("短句", OverlayReplyCoordinator.buildPrompt("短句", "   "))
        assertFalse(OverlayReplyCoordinator.shouldSpeak(""))
        assertTrue(OverlayReplyCoordinator.shouldSpeak("可以帮你"))
    }

    @Test
    fun overlayCollectsChunkedFlowAndChineseFailure() = runBlocking {
        val chunks = OverlayReplyCoordinator.collectReply(flowOf("已", "打开", "蓝牙"))
        assertEquals("已打开蓝牙", chunks)
        val blank = OverlayReplyCoordinator.collectReply(flowOf("  ", ""))
        assertEquals(OverlayReplyCoordinator.EMPTY_REPLY, blank)
        val failed = OverlayReplyCoordinator.collectReply(flow { error("网络中断") })
        assertTrue(failed.startsWith("回答失败："))
        assertTrue(failed.contains("网络中断"))
    }

    @Test
    fun assistStructureJoinsManyNodesWithoutFakingEmpty() {
        val texts = (1..200).map { "节点$it" }.asSequence()
        val joined = AssistStructureReader.collectTexts(texts)
        assertTrue(joined.startsWith("节点1"))
        assertTrue(joined.endsWith("节点200"))
        assertEquals(AssistStructureReader.EMPTY_SCREEN, AssistStructureReader.messageFor("   "))
        assertEquals(AssistStructureReader.EMPTY_SCREEN, AssistStructureReader.messageFor(""))
        assertEquals("相册 设置", AssistStructureReader.messageFor("相册 设置"))
    }

    @Test
    fun suggestionFingerprintStableUnderReorderingPressure() {
        val day = "2026-11-11"
        val ids = (1..50).map { "s$it" }
        val first = SuggestionCardPublisher.fingerprint(ids, day)
        val second = SuggestionCardPublisher.fingerprint(ids, day)
        assertEquals(first, second)
        assertTrue(SuggestionCardPublisher.shouldPublish(null, first, ids.size))
        assertFalse(SuggestionCardPublisher.shouldPublish(first, first, ids.size))
        assertTrue(SuggestionCardPublisher.shouldPublish(first, first + "|extra", ids.size))
        assertFalse(SuggestionCardPublisher.shouldPublish("k", "k2", 0))
    }

    @Test
    fun cameraRoutesUnknownExtraToObjectWithoutFakingImage() {
        assertEquals(CameraCaptureSession.Task.OBJECT, OverlayCameraController.resolveTask("unknown"))
        assertEquals(CameraCaptureSession.Task.OBJECT, OverlayCameraController.resolveTask(""))
        assertEquals(CameraCaptureSession.Task.DOCUMENT, OverlayCameraController.resolveTask("document"))
        assertEquals("识物", OverlayCameraController.titleFor(CameraCaptureSession.Task.OBJECT))
        assertEquals(CameraCaptureSession.NO_IMAGE, OverlayCameraController.noImageMessage())
        assertEquals("未授予相机权限", OverlayCameraController.CAMERA_DENIED)
        assertEquals("无法打开相机", OverlayCameraController.OPEN_FAILED)
    }
}
