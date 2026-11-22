package com.jarvis.pineapple.load

/**
 * 40 轮逐级负荷配置。一轮通过后下一轮必须更重。
 * 第 1 轮只压主功能；第 2 轮轻量覆盖全功能；第 3–40 轮全功能加压。
 * 任务量由会话/消息/记忆数体现；工作与请求频率由并发 worker、
 * 每 worker 无间隔连续创建及指令连发体现；单次体量由 payloadChars 体现。
 */
data class LoadProfile(
    val round: Int,
    val coreOnly: Boolean,
    val overlayChars: Int,
    val overlayChunks: Int,
    val conversations: Int,
    val messagesPerConversation: Int,
    val payloadChars: Int,
    val commandRepeats: Int,
    val memories: Int,
    val workers: Int,
    val createsPerWorker: Int,
    val screenNodes: Int,
    val suggestions: Int,
    val chatTurns: Int,
    val vmCommandRepeats: Int,
    val vncRects: Int,
    val modelPayloadBytes: Int,
    val auditEntries: Int,
    val maxMillis: Long
) {
    init {
        require(round in 1..40)
    }

    companion object {
        val COMMANDS: List<String> = listOf(
            "音量 50",
            "亮度 40",
            "打开微信",
            "打开蓝牙",
            "关闭 wifi",
            "打电话 13800138000",
            "发短信 13900001111 内容你好",
            "提醒我喝水",
            "导航回家"
        )

        fun forRound(round: Int): LoadProfile {
            require(round in 1..40) { "负荷轮次必须是 1..40，实际=$round" }
            return when (round) {
                1 -> LoadProfile(
                    round = 1,
                    coreOnly = true,
                    overlayChars = 8,
                    overlayChunks = 2,
                    conversations = 1,
                    messagesPerConversation = 2,
                    payloadChars = 8,
                    commandRepeats = 1,
                    memories = 0,
                    workers = 1,
                    createsPerWorker = 0,
                    screenNodes = 0,
                    suggestions = 0,
                    chatTurns = 1,
                    vmCommandRepeats = 0,
                    vncRects = 0,
                    modelPayloadBytes = 0,
                    auditEntries = 0,
                    maxMillis = 10_000
                )
                2 -> LoadProfile(
                    round = 2,
                    coreOnly = false,
                    overlayChars = 16,
                    overlayChunks = 3,
                    conversations = 1,
                    messagesPerConversation = 2,
                    payloadChars = 16,
                    commandRepeats = 1,
                    memories = 2,
                    workers = 1,
                    createsPerWorker = 0,
                    screenNodes = 4,
                    suggestions = 2,
                    chatTurns = 2,
                    vmCommandRepeats = 2,
                    vncRects = 2,
                    modelPayloadBytes = 2_048,
                    auditEntries = 502,
                    maxMillis = 30_000
                )
                else -> {
                    val linear = round - 2
                    val concurrent = round >= 11
                    val peak = round >= 21
                    LoadProfile(
                        round = round,
                        coreOnly = false,
                        overlayChars = 16 * linear,
                        overlayChunks = 3 + linear,
                        conversations = round,
                        messagesPerConversation = 2 + linear,
                        payloadChars = if (concurrent) 80 * (round - 10) else 16 + 8 * linear,
                        commandRepeats = 1 + linear,
                        memories = if (peak) 200 + (round - 20) * 120 else 2 * round,
                        workers = if (concurrent) (round - 9).coerceAtMost(8) else 1,
                        createsPerWorker = if (concurrent) (round - 10).coerceAtLeast(1) else 0,
                        screenNodes = 4 * round,
                        suggestions = round,
                        chatTurns = round,
                        vmCommandRepeats = round,
                        vncRects = round,
                        modelPayloadBytes = 2_048 + round * 1_024,
                        auditEntries = 500 + round,
                        maxMillis = when {
                            peak -> 120_000
                            concurrent -> 90_000
                            else -> 60_000
                        }
                    )
                }
            }
        }

        fun assertEscalates() {
            var previousWeight = 0
            for (round in 1..40) {
                val weight = forRound(round).weight()
                check(weight > previousWeight) {
                    "第 $round 轮负荷 $weight 必须大于上一轮 $previousWeight"
                }
                previousWeight = weight
            }
        }
    }

    fun weight(): Int =
        overlayChars + overlayChunks + conversations * messagesPerConversation * (payloadChars / 8 + 1) +
            commandRepeats * COMMANDS.size + memories + workers * createsPerWorker * 4 +
            screenNodes + suggestions + chatTurns * 8 + vmCommandRepeats * 2 + vncRects +
            modelPayloadBytes / 1_024 + auditEntries
}
