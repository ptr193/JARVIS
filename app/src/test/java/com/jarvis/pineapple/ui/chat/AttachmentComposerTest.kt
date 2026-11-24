package com.jarvis.pineapple.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentComposerTest {

    @Test
    fun userTextOnlyIsSentAsIs() {
        assertEquals("你好", ChatViewModel.composeMessageText("  你好  ", emptyList()))
    }

    @Test
    fun attachmentsOnlyDoNotProduceEmptyMessage() {
        assertEquals(
            "【文件附件】a.txt",
            ChatViewModel.composeMessageText("", listOf("【文件附件】a.txt"))
        )
    }

    @Test
    fun typedTextStaysBeforeAttachmentContent() {
        assertEquals(
            "帮我总结这个文件\n\n【文件附件】a.txt 内容如下：\nhello",
            ChatViewModel.composeMessageText(
                "帮我总结这个文件",
                listOf("【文件附件】a.txt 内容如下：\nhello")
            )
        )
    }

    @Test
    fun multipleAttachmentsKeepSelectionOrderAndBlankBlocksAreDropped() {
        assertEquals(
            "第一段\n\n第三段",
            ChatViewModel.composeMessageText("", listOf("第一段", "   ", "第三段"))
        )
    }
}
