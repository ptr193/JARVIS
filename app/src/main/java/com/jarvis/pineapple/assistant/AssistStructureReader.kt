package com.jarvis.pineapple.assistant

object AssistStructureReader {
    const val EMPTY_SCREEN = "未能读取当前屏幕内容"
    const val ASSIST_UNAVAILABLE = "识屏不可用"

    fun collectTexts(nodeTexts: Sequence<String>): String =
        nodeTexts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    fun messageFor(extracted: String): String =
        extracted.trim().ifBlank { EMPTY_SCREEN }
}
