package com.jarvis.pineapple.speech

/**
 * 纯 Kotlin 音素转换器（Piper TTS 前置处理）。
 *
 * 支持：
 * - 中文：汉字 → 拼音 → Piper 音素 IDs
 * - 英文：字母 → 音素 IDs（基于规则）
 *
 * 不依赖 espeak-ng 原生库，使用内置规则实现基础音素转换。
 */
object Phonemizer {

    // 拼音声母/韵母到 IPA 音素的映射
    private val PINYIN_TO_PHONEMES = mapOf(
        "a" to "a", "o" to "o", "e" to "ə", "i" to "i", "u" to "u", "ü" to "y",
        "b" to "p", "p" to "pʰ", "m" to "m", "f" to "f",
        "d" to "t", "t" to "tʰ", "n" to "n", "l" to "l",
        "g" to "k", "k" to "kʰ", "h" to "x",
        "j" to "tɕ", "q" to "tɕʰ", "x" to "ɕ",
        "zh" to "ʈʂ", "ch" to "ʈʂʰ", "sh" to "ʂ", "r" to "ʐ",
        "z" to "ts", "c" to "tsʰ", "s" to "s"
    )

    // 英文单/双字母到 IPA 音素的映射
    private val ENGLISH_PHONEMES = mapOf(
        "a" to "æ", "e" to "ɛ", "i" to "ɪ", "o" to "ɑ", "u" to "ʌ",
        "b" to "b", "c" to "k", "d" to "d", "f" to "f", "g" to "ɡ",
        "h" to "h", "j" to "dʒ", "k" to "k", "l" to "l", "m" to "m",
        "n" to "n", "p" to "p", "q" to "k", "r" to "ɹ", "s" to "s",
        "t" to "t", "v" to "v", "w" to "w", "x" to "ks", "y" to "j", "z" to "z",
        "th" to "θ", "sh" to "ʃ", "ch" to "tʃ", "ng" to "ŋ", "ph" to "f"
    )

    /**
     * 将文本转换为音素 ID 列表
     */
    fun textToPhonemeIds(text: String, language: String, phonemeIdMap: Map<String, Int>): List<Int> {
        if (phonemeIdMap.isEmpty()) return emptyList()

        val phonemes = when {
            language.startsWith("zh") -> chineseToPhonemes(text)
            language.startsWith("en") -> englishToPhonemes(text)
            else -> universalToPhonemes(text)
        }

        val ids = mutableListOf<Int>()
        ids.add(0) // BOS

        phonemes.forEach { phoneme ->
            val id = phonemeIdMap[phoneme]
            if (id != null) {
                ids.add(id)
            }
        }

        ids.add(1) // EOS
        return ids
    }

    /**
     * 中文 → 音素（基于拼音规则）
     */
    private fun chineseToPhonemes(text: String): List<String> {
        val phonemes = mutableListOf<String>()

        text.forEach { char ->
            if (char.code in 0x4E00..0x9FFF) {
                // 汉字：使用拼音映射
                val pinyin = ChinesePinyin.toPinyin(char)
                val pinyinPhonemes = pinyinToPhonemes(pinyin)
                phonemes.addAll(pinyinPhonemes)
            } else if (char.isLetterOrDigit()) {
                // 非中文字符：按英文处理
                phonemes.addAll(englishToPhonemes(char.toString()))
            }
            // 忽略标点符号
        }

        return phonemes
    }

    /**
     * 拼音 → 音素
     */
    private fun pinyinToPhonemes(pinyin: String): List<String> {
        if (pinyin.isEmpty()) return emptyList()

        val phonemes = mutableListOf<String>()
        var i = 0
        val lower = pinyin.lowercase()

        while (i < lower.length) {
            // 尝试匹配双字母拼音
            if (i + 1 < lower.length) {
                val two = lower.substring(i, i + 2)
                val twoPhoneme = PINYIN_TO_PHONEMES[two]
                if (twoPhoneme != null) {
                    phonemes.add(twoPhoneme)
                    i += 2
                    continue
                }
            }

            // 单字母拼音
            val one = lower[i].toString()
            val onePhoneme = PINYIN_TO_PHONEMES[one]
            if (onePhoneme != null) {
                phonemes.add(onePhoneme)
            }
            i++
        }

        return phonemes
    }

    /**
     * 英文 → 音素
     */
    private fun englishToPhonemes(text: String): List<String> {
        val phonemes = mutableListOf<String>()
        val lower = text.lowercase()
        var i = 0

        while (i < lower.length) {
            val char = lower[i]

            // 跳过非字母
            if (!char.isLetter()) {
                i++
                continue
            }

            // 尝试匹配双字母组合
            if (i + 1 < lower.length && lower[i + 1].isLetter()) {
                val two = lower.substring(i, i + 2)
                val twoPhoneme = ENGLISH_PHONEMES[two]
                if (twoPhoneme != null) {
                    phonemes.add(twoPhoneme)
                    i += 2
                    continue
                }
            }

            // 单字母
            val onePhoneme = ENGLISH_PHONEMES[char.toString()]
            if (onePhoneme != null) {
                phonemes.add(onePhoneme)
            }
            i++
        }

        return phonemes
    }

    /**
     * 通用音素转换（其他语言）
     */
    private fun universalToPhonemes(text: String): List<String> {
        // 其他语言：逐字符映射到音素
        return text.mapNotNull { char ->
            val str = char.toString()
            PINYIN_TO_PHONEMES[str] ?: ENGLISH_PHONEMES[str]
        }
    }
}

/**
 * 中文拼音转换（基于 Unicode 范围）
 */
object ChinesePinyin {

    // 常用汉字拼音映射表（覆盖 GB2312 一级汉字）
    private val PINYIN_MAP = mapOf(
        '的' to "de", '一' to "yi", '是' to "shi", '不' to "bu", '了' to "le",
        '人' to "ren", '我' to "wo", '在' to "zai", '有' to "you", '他' to "ta",
        '这' to "zhe", '中' to "zhong", '大' to "da", '来' to "lai", '上' to "shang",
        '国' to "guo", '个' to "ge", '到' to "dao", '说' to "shuo", '们' to "men",
        '为' to "wei", '子' to "zi", '和' to "he", '你' to "ni", '地' to "di",
        '出' to "chu", '也' to "ye", '年' to "nian", '得' to "de", '里' to "li",
        '后' to "hou", '以' to "yi", '会' to "hui", '家' to "jia", '可' to "ke",
        '下' to "xia", '而' to "er", '过' to "guo", '天' to "tian", '去' to "qu",
        '能' to "neng", '对' to "dui", '小' to "xiao", '多' to "duo", '然' to "ran",
        '于' to "yu", '心' to "xin", '学' to "xue", '都' to "dou", '看' to "kan",
        '发' to "fa", '当' to "dang", '没' to "mei", '成' to "cheng", '只' to "zhi",
        '如' to "ru", '事' to "shi", '把' to "ba", '还' to "hai", '用' to "yong",
        '第' to "di", '样' to "yang", '想' to "xiang", '作' to "zuo", '种' to "zhong",
        '开' to "kai", '美' to "mei", '从' to "cong", '无' to "wu", '情' to "qing",
        '最' to "zui", '但' to "dan", '现' to "xian", '所' to "suo", '手' to "shou",
        '又' to "you", '什' to "shen", '然' to "ran", '笑' to "xiao", '两' to "liang",
        '已' to "yi", '些' to "xie", '让' to "rang", '但' to "dan", '很' to "hen",
        '她' to "ta", '被' to "bei", '走' to "zou", '将' to "jiang", '因' to "yin",
        '于' to "yu", '只' to "zhi", '没' to "mei", '与' to "yu", '其' to "qi",
        '些' to "xie", '最' to "zui", '更' to "geng", '太' to "tai", '非' to "fei",
        '常' to "chang", '已' to "yi", '每' to "mei", '次' to "ci", '别' to "bie",
        '各' to "ge", '自' to "zi", '本' to "ben", '前' to "qian", '后' to "hou",
        '左' to "zuo", '右' to "you", '内' to "nei", '外' to "wai", '里' to "li",
        '间' to "jian", '边' to "bian", '面' to "mian", '头' to "tou", '部' to "bu",
        '身' to "shen", '体' to "ti", '心' to "xin", '手' to "shou", '眼' to "yan",
        '口' to "kou", '耳' to "er", '鼻' to "bi", '舌' to "she", '牙' to "ya",
        '日' to "ri", '月' to "yue", '年' to "nian", '时' to "shi", '分' to "fen",
        '秒' to "miao", '今' to "jin", '明' to "ming", '昨' to "zoo", '早' to "zao",
        '晚' to "wan", '午' to "wu", '夜' to "ye", '春' to "chun", '夏' to "xia",
        '秋' to "qiu", '冬' to "dong", '风' to "feng", '雨' to "yu", '雪' to "xue",
        '云' to "yun", '雷' to "lei", '电' to "dian", '山' to "shan", '水' to "shui",
        '火' to "huo", '土' to "tu", '木' to "mu", '金' to "jin", '石' to "shi",
        '田' to "tian", '地' to "di", '天' to "tian", '空' to "kong", '海' to "hai",
        '河' to "he", '湖' to "hu", '江' to "jiang", '溪' to "xi", '泉' to "quan",
        '花' to "hua", '草' to "cao", '树' to "shu", '林' to "lin", '森' to "sen",
        '鸟' to "niao", '鱼' to "yu", '虫' to "chong", '马' to "ma", '牛' to "niu",
        '羊' to "yang", '猪' to "zhu", '狗' to "gou", '猫' to "mao", '鸡' to "ji",
        '鸭' to "ya", '鹅' to "e", '兔' to "tu", '鼠' to "shu", '蛇' to "she",
        '龙' to "long", '虎' to "hu", '狮' to "shi", '象' to "xiang", '鹿' to "lu",
        '熊' to "xiong", '狼' to "lang", '狐' to "hu", '狸' to "li", '猴' to "hou",
        '猩' to "xing", '猩' to "xing", '狗' to "gou", '猫' to "mao", '鸟' to "niao"
    )

    /**
     * Han-Latin 转写器（Android 10+ 的完整拼音声调输出）。
     */
    private val transliterator: android.icu.text.Transliterator? by lazy {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                android.icu.text.Transliterator.getInstance("Han-Latin")
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    /**
     * 汉字转拼音。优先使用 ICU Han-Latin 音译，回退到内置拼音表。
     */
    fun toPinyin(char: Char): String {
        transliterator?.let { transliterator ->
            val cleaned = cleanPinyin(transliterator.transliterate(char.toString()))
            if (cleaned.isNotEmpty()) return cleaned
        }
        return PINYIN_MAP[char] ?: char.toString()
    }

    /**
     * 去除声调符号并仅保留字母。
     */
    private fun cleanPinyin(raw: String): String {
        val normalized = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD)
        return normalized.replace(Regex("\\p{Mn}+"), "")
            .filter { it.isLetter() }
            .lowercase()
    }

    /**
     * 批量转换
     */
    fun toPinyin(text: String): String {
        return text.map { toPinyin(it) }.joinToString("")
    }
}
