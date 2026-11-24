package com.jarvis.pineapple.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun twentyImagesFitAndTheTwentyFirstIsRejected() {
        assertEquals(20, ChatViewModel.acceptedAttachmentCount(currentCount = 0, requestedCount = 20))
        assertEquals(20, ChatViewModel.acceptedAttachmentCount(currentCount = 0, requestedCount = 21))
        assertEquals(0, ChatViewModel.acceptedAttachmentCount(currentCount = 20, requestedCount = 1))
    }

    @Test
    fun overflowSelectionOnlyAcceptsRemainingCapacity() {
        assertEquals(1, ChatViewModel.acceptedAttachmentCount(currentCount = 19, requestedCount = 4))
        assertEquals(0, ChatViewModel.acceptedAttachmentCount(currentCount = 25, requestedCount = 4))
    }

    @Test
    fun twentyImagePathsKeepSelectionOrderAfterTypedText() {
        val blocks = (1..20).map { index ->
            ChatViewModel.buildAttachmentText(
                displayName = "$index.png",
                mimeType = "image/png",
                size = index.toLong(),
                sha256 = index.toString().padStart(64, '0'),
                absolutePath = "/data/user/0/app/files/attachments/$index.png",
                isImage = true
            )
        }
        val content = ChatViewModel.composeMessageText("按顺序处理", blocks)

        assertTrue(content.startsWith("按顺序处理\n\n"))
        assertEquals(20, Regex("【图片附件】").findAll(content).count())
        (1..19).forEach { index ->
            val current = content.indexOf("attachments/$index.png")
            val next = content.indexOf("attachments/${index + 1}.png")
            assertTrue("第 $index 张图片应排在第 ${index + 1} 张之前", current in 0 until next)
        }
    }

    @Test
    fun textAttachmentContainsRealAbsolutePathAndRetainsExcerpt() {
        val path = "/storage/emulated/0/Documents/notes.txt"
        val block = ChatViewModel.buildAttachmentText(
            displayName = "notes.txt",
            mimeType = "text/plain",
            size = 5,
            sha256 = "a".repeat(64),
            absolutePath = path,
            textExcerpt = "hello"
        )

        assertTrue(block.contains("绝对路径：$path"))
        assertTrue(block.contains("内容如下：\nhello"))
    }

    @Test
    fun imageAttachmentContainsPathAndExplicitlySaysItWasNotUploaded() {
        val path = "/data/user/0/app/files/attachments/uuid.jpg"
        val block = ChatViewModel.buildAttachmentText(
            displayName = "photo.jpg",
            mimeType = "image/jpeg",
            size = 128,
            sha256 = "b".repeat(64),
            absolutePath = path,
            isImage = true
        )

        assertTrue(block.contains("绝对路径：$path"))
        assertTrue(block.contains("图片未上传；可沿绝对路径用手机文件能力处理"))
        assertFalse(block.contains("请识别并回答图片内容"))
    }

    @Test
    fun copiedFileKeepsOnlySafeExtension() {
        assertEquals(".jpg", ChatViewModel.safeAttachmentExtension("photo.JPG"))
        assertEquals(".gz", ChatViewModel.safeAttachmentExtension("archive.tar.gz"))
        assertEquals("", ChatViewModel.safeAttachmentExtension("no-extension"))
        assertEquals("", ChatViewModel.safeAttachmentExtension("unsafe.jp/g"))
        assertEquals("", ChatViewModel.safeAttachmentExtension("unsafe.verylongextension"))
    }

    @Test
    fun viewModelNeverCreatesOrPassesEncodedImageInput() {
        val source = readSource("app/src/main/java/com/jarvis/pineapple/ui/chat/ChatViewModel.kt")

        assertFalse(source.contains("base64", ignoreCase = true))
        assertFalse(source.contains("ImageInput"))
        assertFalse(source.contains("BitmapFactory"))
        assertTrue(source.contains("chatEngine.sendUserMessage(content).collect"))
        assertFalse(Regex("sendUserMessage\\s*\\([^)]*image\\s*=").containsMatchIn(source))
    }

    @Test
    fun screenUsesMultipleDocumentPickerAndSafFilesArePrivatelyCopied() {
        val screen = readSource("app/src/main/java/com/jarvis/pineapple/ui/chat/ChatScreen.kt")
        val viewModel = readSource("app/src/main/java/com/jarvis/pineapple/ui/chat/ChatViewModel.kt")

        assertTrue(screen.contains("ActivityResultContracts.OpenMultipleDocuments()"))
        assertTrue(screen.contains("viewModel.addAttachments(uris)"))
        assertTrue(viewModel.contains("File(context.filesDir, \"attachments\")"))
        assertTrue(viewModel.contains("UUID.randomUUID().toString() + safeAttachmentExtension(name)"))
    }

    private fun readSource(relativePath: String): String {
        val file = listOf(File(relativePath), File("../$relativePath"))
            .firstOrNull { it.exists() }
            ?: error("无法定位源文件: $relativePath")
        return file.readText()
    }
}
