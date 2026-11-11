package com.jarvis.pineapple.chat

/**
 * 极简指令解析（纯函数）。失败不伪造可执行指令。
 */
object AssistantCommandParser {

    data class ParsedCommand(
        val action: String,
        val parameters: Map<String, String>,
        val isExecutable: Boolean,
        val displayLabel: String
    )

    val CHAT_SHORTCUTS: List<Pair<String, String>> = listOf(
        "音量 50" to "音量",
        "亮度 50" to "亮度",
        "打开微信" to "打开微信",
        "提醒我喝水" to "提醒",
        "打开蓝牙" to "蓝牙",
        "导航回家" to "导航"
    )

    private val COMMAND_PREFIXES = listOf(
        "打开", "开启", "启动", "关闭", "关掉", "open", "start", "close",
        "音量", "volume", "亮度", "brightness",
        "打电话", "拨打", "call",
        "发短信", "发送短信", "send sms",
        "提醒", "闹钟", "remind",
        "导航", "navigate",
        "wifi", "wi-fi", "无线网", "蓝牙", "bluetooth"
    )

    private val APP_ALIASES: Map<String, String> = mapOf(
        "微信" to "com.tencent.mm",
        "wechat" to "com.tencent.mm",
        "支付宝" to "com.eg.android.AlipayGphone",
        "alipay" to "com.eg.android.AlipayGphone",
        "QQ" to "com.tencent.mobileqq",
        "qq" to "com.tencent.mobileqq",
        "淘宝" to "com.taobao.taobao",
        "抖音" to "com.ss.android.ugc.aweme",
        "设置" to "com.android.settings",
        "settings" to "com.android.settings",
        "相机" to "com.android.camera",
        "电话" to "com.android.dialer",
        "短信" to "com.android.mms",
        "浏览器" to "com.android.chrome"
    )

    fun isDirectCommand(message: String): Boolean {
        val trimmed = message.trim()
        if (trimmed.isEmpty() || trimmed.length > 48) return false
        if (parse(trimmed).action == "UNKNOWN") return false
        return COMMAND_PREFIXES.any { trimmed.startsWith(it, ignoreCase = true) }
    }

    fun parse(message: String): ParsedCommand {
        val raw = message.trim()
        if (raw.isEmpty()) return unknown()
        val lower = raw.lowercase()

        parseCall(raw, lower)?.let { return it }
        parseSms(raw, lower)?.let { return it }
        parseReminder(raw, lower)?.let { return it }
        parseNavigate(raw, lower)?.let { return it }
        parseWifi(raw, lower)?.let { return it }
        parseBluetooth(raw, lower)?.let { return it }
        parseVolume(raw, lower)?.let { return it }
        parseBrightness(raw, lower)?.let { return it }
        parseOpenApp(raw, lower)?.let { return it }
        parseCloseApp(raw, lower)?.let { return it }
        return unknown()
    }

    fun resolvePackage(appName: String): String? {
        val key = appName.trim()
        if (key.isEmpty()) return null
        APP_ALIASES[key]?.let { return it }
        APP_ALIASES.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.let { return it.value }
        return if (key.contains('.')) key else null
    }

    fun volumeFromPercent(level: Int): Int = (level.coerceIn(0, 100) * 15 / 100)

    fun brightnessFromPercent(level: Int): Int = (level.coerceIn(0, 100) * 255 / 100)

    fun reminderDelayMs(text: String): Long {
        val minutes = Regex("(\\d+)\\s*分钟").find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (minutes != null) return minutes.coerceIn(1, 24 * 60) * 60_000L
        val hours = Regex("(\\d+)\\s*小时").find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (hours != null) return hours.coerceIn(1, 24) * 3_600_000L
        return 5 * 60_000L
    }

    private fun parseCall(raw: String, lower: String): ParsedCommand? {
        if (!containsAny(lower, "打电话", "拨打", "call")) return null
        val number = Regex("\\d{3,}").find(raw)?.value.orEmpty()
        if (number.isEmpty()) {
            return ParsedCommand("MAKE_CALL", emptyMap(), false, "未提供电话号码")
        }
        return ParsedCommand("MAKE_CALL", mapOf("number" to number), true, "拨打电话")
    }

    private fun parseSms(raw: String, lower: String): ParsedCommand? {
        if (!containsAny(lower, "发短信", "发送短信", "send sms")) return null
        val number = Regex("\\d{3,}").find(raw)?.value.orEmpty()
        val body = extractAfter(raw, listOf("内容", "说", "：", ":")).ifBlank {
            raw.replace(Regex("发短信|发送短信|send sms|给|到", RegexOption.IGNORE_CASE), "")
                .replace(number, "")
                .trim()
        }
        if (number.isEmpty()) {
            return ParsedCommand("SEND_SMS", emptyMap(), false, "未提供短信号码")
        }
        if (body.isEmpty()) {
            return ParsedCommand("SEND_SMS", mapOf("number" to number), false, "未提供短信内容")
        }
        return ParsedCommand("SEND_SMS", mapOf("number" to number, "body" to body), true, "发送短信")
    }

    private fun parseReminder(raw: String, lower: String): ParsedCommand? {
        if (!containsAny(lower, "提醒", "闹钟", "remind")) return null
        val title = raw.replace(Regex("提醒我|提醒|闹钟|remind me|remind", RegexOption.IGNORE_CASE), "").trim()
            .ifBlank { "提醒事项" }
        return ParsedCommand(
            "ADD_REMINDER",
            mapOf("title" to title, "delayMs" to reminderDelayMs(raw).toString()),
            true,
            "添加提醒"
        )
    }

    private fun parseNavigate(raw: String, lower: String): ParsedCommand? {
        if (!containsAny(lower, "导航", "navigate")) return null
        val dest = raw.replace(Regex("导航到|导航去|导航|navigate to|navigate", RegexOption.IGNORE_CASE), "")
            .trim()
        if (dest.isEmpty() || dest == "目的地") {
            return ParsedCommand("NAVIGATE", emptyMap(), false, "未提供目的地")
        }
        return ParsedCommand("NAVIGATE", mapOf("destination" to dest), true, "导航")
    }

    private fun parseWifi(raw: String, lower: String): ParsedCommand? {
        if (!containsAny(lower, "wifi", "wi-fi", "无线网")) return null
        val enabled = !containsAny(lower, "关", "关闭", "关掉", "off", "disable")
        return ParsedCommand("SET_WIFI", mapOf("enabled" to enabled.toString()), true, if (enabled) "打开 Wi-Fi" else "关闭 Wi-Fi")
    }

    private fun parseBluetooth(raw: String, lower: String): ParsedCommand? {
        if (!containsAny(lower, "蓝牙", "bluetooth")) return null
        val enabled = !containsAny(lower, "关", "关闭", "关掉", "off", "disable")
        return ParsedCommand("SET_BLUETOOTH", mapOf("enabled" to enabled.toString()), true, if (enabled) "打开蓝牙" else "关闭蓝牙")
    }

    private fun parseVolume(raw: String, lower: String): ParsedCommand? {
        if (!containsAny(lower, "音量", "volume")) return null
        val level = extractNumber(raw)
        return ParsedCommand("SET_VOLUME", mapOf("level" to level.toString()), true, "调节音量")
    }

    private fun parseBrightness(raw: String, lower: String): ParsedCommand? {
        if (!containsAny(lower, "亮度", "brightness")) return null
        val level = extractNumber(raw)
        return ParsedCommand("SET_BRIGHTNESS", mapOf("level" to level.toString()), true, "调节亮度")
    }

    private fun parseOpenApp(raw: String, lower: String): ParsedCommand? {
        if (!containsAny(lower, "打开", "开启", "启动", "open", "start")) return null
        val app = stripKeywords(raw, listOf("打开", "开启", "启动", "open", "start"))
        if (app.isEmpty()) {
            return ParsedCommand("OPEN_APP", emptyMap(), false, "未指定应用名称")
        }
        val pkg = resolvePackage(app)
        return if (pkg == null) {
            ParsedCommand("OPEN_APP", mapOf("app" to app), false, "无法识别应用：$app")
        } else {
            ParsedCommand("OPEN_APP", mapOf("app" to app, "package" to pkg), true, "打开$app")
        }
    }

    private fun parseCloseApp(raw: String, lower: String): ParsedCommand? {
        if (!containsAny(lower, "关闭", "关掉", "close")) return null
        val app = stripKeywords(raw, listOf("关闭", "关掉", "close"))
        return ParsedCommand("CLOSE_APP", mapOf("app" to app), false, "无法在后台强制关闭应用")
    }

    private fun unknown() = ParsedCommand("UNKNOWN", emptyMap(), false, "")

    private fun containsAny(text: String, vararg keywords: String): Boolean =
        keywords.any { text.contains(it, ignoreCase = true) }

    private fun extractNumber(message: String): Int =
        Regex("\\d+").find(message)?.value?.toIntOrNull()?.coerceIn(0, 100) ?: 50

    private fun stripKeywords(message: String, keywords: List<String>): String {
        var result = message
        for (keyword in keywords) {
            result = result.replace(keyword, "", ignoreCase = true)
        }
        return result.trim()
    }

    private fun extractAfter(raw: String, markers: List<String>): String {
        for (marker in markers) {
            val idx = raw.indexOf(marker)
            if (idx >= 0) return raw.substring(idx + marker.length).trim()
        }
        return ""
    }
}
