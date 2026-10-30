package com.jarvis.pineapple.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 能力域划分（REQ-13）
 */
enum class CapabilityDomain {
    CODE, MATH_LOGIC, CHINESE_WRITING, MULTILINGUAL_TRANSLATION,
    PROFESSIONAL_KNOWLEDGE, IMAGE_MULTIMODAL, LONG_TEXT_PROCESSING
}

/**
 * 能力评级
 */
enum class CapabilityRating {
    STRONG, MEDIUM, WEAK, DEFICIENT
}

/**
 * 模型能力画像
 */
data class ModelCapabilityProfile(
    val modelId: String,
    val modelName: String,
    val ratings: Map<CapabilityDomain, CapabilityRating> = emptyMap()
) {
    fun getRating(domain: CapabilityDomain): CapabilityRating {
        return ratings[domain] ?: CapabilityRating.MEDIUM
    }

    fun isStrongIn(domain: CapabilityDomain): Boolean {
        return getRating(domain) == CapabilityRating.STRONG
    }

    fun isWeakIn(domain: CapabilityDomain): Boolean {
        val rating = getRating(domain)
        return rating == CapabilityRating.WEAK || rating == CapabilityRating.DEFICIENT
    }
}

/**
 * 调度记录
 */
data class SchedulingRecord(
    val timestamp: Long = System.currentTimeMillis(),
    val userRequest: String,
    val primaryModelId: String,
    val primaryModelName: String,
    val domain: CapabilityDomain,
    val primaryRating: CapabilityRating,
    val assistantModelId: String?,
    val assistantModelName: String?,
    val wasScheduled: Boolean,
    val reason: String
)

/**
 * 模型能力短板自动调度器（REQ-13）。
 */
class ModelCapabilityScheduler(
    private val modelScheduler: ModelScheduler
) {

    private val _capabilityProfiles = MutableStateFlow<Map<String, ModelCapabilityProfile>>(emptyMap())
    val capabilityProfiles: StateFlow<Map<String, ModelCapabilityProfile>> = _capabilityProfiles.asStateFlow()

    fun registerProfile(profile: ModelCapabilityProfile) {
        val current = _capabilityProfiles.value.toMutableMap()
        current[profile.modelId] = profile
        _capabilityProfiles.value = current
    }

    fun registerProfiles(profiles: List<ModelCapabilityProfile>) {
        val current = _capabilityProfiles.value.toMutableMap()
        profiles.forEach { current[it.modelId] = it }
        _capabilityProfiles.value = current
    }

    fun getProfile(modelId: String): ModelCapabilityProfile? {
        return _capabilityProfiles.value[modelId]
    }

    fun identifyDomain(request: String): CapabilityDomain {
        val lower = request.lowercase()
        return when {
            lower.containsAny("代码", "编程", "写代码", "函数", "方法", "类", "接口",
                "bug", "调试", "debug", "程序", "脚本", "开发", "算法", "编译") ||
            lower.containsAny("code", "program", "function", "method", "class", "api",
                "debug", "script", "develop", "compile") -> CapabilityDomain.CODE

            lower.containsAny("数学", "计算", "算", "公式", "证明", "推理", "逻辑",
                "方程", "微积分", "概率", "统计", "几何") ||
            lower.containsAny("math", "calculate", "compute", "formula", "prove",
                "logic", "equation", "calculus", "probability", "statistics") -> CapabilityDomain.MATH_LOGIC

            lower.containsAny("写作", "写文章", "文案", "公文", "润色", "改写", "诗", "词",
                "散文", "小说", "作文", "周报", "总结", "报告") -> CapabilityDomain.CHINESE_WRITING

            lower.containsAny("翻译", "translate", "英文", "中文", "日语", "韩语", "法语",
                "德语", "西班牙语", "俄语", "外语", "译文", "口译") -> CapabilityDomain.MULTILINGUAL_TRANSLATION

            lower.containsAny("医学", "法律", "金融", "经济", "物理", "化学", "生物",
                "历史", "哲学", "心理", "教育", "工程", "建筑") -> CapabilityDomain.PROFESSIONAL_KNOWLEDGE

            lower.containsAny("看图", "图片", "图像", "照片", "识别", "ocr", "表格",
                "截图", "画面", "视频", "多模态") || lower.containsAny("image", "picture",
                "photo", "vision", "screenshot") -> CapabilityDomain.IMAGE_MULTIMODAL

            lower.containsAny("长文", "论文", "长文档", "摘要", "总结", "概括", "归纳",
                "长篇", "章节", "全书", "report", "paper", "summary") -> CapabilityDomain.LONG_TEXT_PROCESSING

            else -> {
                if (request.length > 500) CapabilityDomain.LONG_TEXT_PROCESSING
                else CapabilityDomain.CHINESE_WRITING
            }
        }
    }

    data class SchedulingDecision(
        val shouldSchedule: Boolean,
        val domain: CapabilityDomain,
        val primaryModel: AiModel,
        val primaryProfile: ModelCapabilityProfile?,
        val assistantModel: AiModel?,
        val assistantProfile: ModelCapabilityProfile?,
        val reason: String
    )

    fun analyzeAndDecide(request: String): SchedulingDecision {
        val domain = identifyDomain(request)
        val primaryModel = modelScheduler.getActive()
        val primaryProfile = getProfile(primaryModel.info.id)

        val primaryRating = primaryProfile?.getRating(domain) ?: CapabilityRating.MEDIUM

        if (primaryRating == CapabilityRating.STRONG || primaryRating == CapabilityRating.MEDIUM) {
            return SchedulingDecision(
                shouldSchedule = false,
                domain = domain,
                primaryModel = primaryModel,
                primaryProfile = primaryProfile,
                assistantModel = null,
                assistantProfile = null,
                reason = "主模型 ${primaryModel.info.name} 在 ${domain.displayName} 能力域表现${primaryRating.label}，可直接处理"
            )
        }

        val assistant = findAssistantModel(domain, excludeModelId = primaryModel.info.id)

        return if (assistant != null) {
            SchedulingDecision(
                shouldSchedule = true,
                domain = domain,
                primaryModel = primaryModel,
                primaryProfile = primaryProfile,
                assistantModel = assistant.first,
                assistantProfile = assistant.second,
                reason = "主模型 ${primaryModel.info.name} 在 ${domain.displayName} 能力域存在短板，已调度强项模型 ${assistant.first.info.name} 协助完成"
            )
        } else {
            SchedulingDecision(
                shouldSchedule = false,
                domain = domain,
                primaryModel = primaryModel,
                primaryProfile = primaryProfile,
                assistantModel = null,
                assistantProfile = null,
                reason = "主模型 ${primaryModel.info.name} 在 ${domain.displayName} 能力域存在短板，但无可用辅助模型，建议导入/配置强项模型"
            )
        }
    }

    private fun findAssistantModel(
        domain: CapabilityDomain,
        excludeModelId: String
    ): Pair<AiModel, ModelCapabilityProfile?>? {
        val candidates = modelScheduler.availableModels.value
            .filter { it.info.id != excludeModelId }

        var bestModel: AiModel? = null
        var bestProfile: ModelCapabilityProfile? = null
        var bestScore = -1

        for (model in candidates) {
            val profile = getProfile(model.info.id)
            val rating = profile?.getRating(domain) ?: CapabilityRating.MEDIUM
            val score = when (rating) {
                CapabilityRating.STRONG -> 4
                CapabilityRating.MEDIUM -> 2
                CapabilityRating.WEAK -> 1
                CapabilityRating.DEFICIENT -> 0
            }

            val localBonus = if (model.info.source == ModelSource.LOCAL_USER ||
                model.info.source == ModelSource.LOCAL_LITERT) 1 else 0

            val totalScore = score + localBonus

            if (totalScore > bestScore) {
                bestScore = totalScore
                bestModel = model
                bestProfile = profile
            }
        }

        return if (bestScore >= 4 && bestModel != null) {
            Pair(bestModel, bestProfile)
        } else null
    }

    fun registerDefaultProfiles() {
        val profiles = listOf(
            ModelCapabilityProfile(
                modelId = "gemma4_e2b_2_5b",
                modelName = "Gemma 4 E2B 2.5B",
                ratings = mapOf(
                    CapabilityDomain.CODE to CapabilityRating.MEDIUM,
                    CapabilityDomain.MATH_LOGIC to CapabilityRating.MEDIUM,
                    CapabilityDomain.CHINESE_WRITING to CapabilityRating.MEDIUM,
                    CapabilityDomain.MULTILINGUAL_TRANSLATION to CapabilityRating.STRONG,
                    CapabilityDomain.PROFESSIONAL_KNOWLEDGE to CapabilityRating.MEDIUM,
                    CapabilityDomain.IMAGE_MULTIMODAL to CapabilityRating.STRONG,
                    CapabilityDomain.LONG_TEXT_PROCESSING to CapabilityRating.WEAK
                )
            ),
            ModelCapabilityProfile(
                modelId = "gemma4_12b",
                modelName = "Gemma 4 12B",
                ratings = mapOf(
                    CapabilityDomain.CODE to CapabilityRating.STRONG,
                    CapabilityDomain.MATH_LOGIC to CapabilityRating.STRONG,
                    CapabilityDomain.CHINESE_WRITING to CapabilityRating.STRONG,
                    CapabilityDomain.MULTILINGUAL_TRANSLATION to CapabilityRating.STRONG,
                    CapabilityDomain.PROFESSIONAL_KNOWLEDGE to CapabilityRating.STRONG,
                    CapabilityDomain.IMAGE_MULTIMODAL to CapabilityRating.STRONG,
                    CapabilityDomain.LONG_TEXT_PROCESSING to CapabilityRating.MEDIUM
                )
            ),
            ModelCapabilityProfile(
                modelId = "lfm2_5_1_7b",
                modelName = "LFM 2.5 1.2B Instruct",
                ratings = mapOf(
                    CapabilityDomain.CODE to CapabilityRating.MEDIUM,
                    CapabilityDomain.MATH_LOGIC to CapabilityRating.WEAK,
                    CapabilityDomain.CHINESE_WRITING to CapabilityRating.WEAK,
                    CapabilityDomain.MULTILINGUAL_TRANSLATION to CapabilityRating.MEDIUM,
                    CapabilityDomain.PROFESSIONAL_KNOWLEDGE to CapabilityRating.WEAK,
                    CapabilityDomain.IMAGE_MULTIMODAL to CapabilityRating.DEFICIENT,
                    CapabilityDomain.LONG_TEXT_PROCESSING to CapabilityRating.WEAK
                )
            ),
            ModelCapabilityProfile(
                modelId = "qwen3_0_6b",
                modelName = "Qwen 3 0.6B",
                ratings = mapOf(
                    CapabilityDomain.CODE to CapabilityRating.WEAK,
                    CapabilityDomain.MATH_LOGIC to CapabilityRating.MEDIUM,
                    CapabilityDomain.CHINESE_WRITING to CapabilityRating.STRONG,
                    CapabilityDomain.MULTILINGUAL_TRANSLATION to CapabilityRating.STRONG,
                    CapabilityDomain.PROFESSIONAL_KNOWLEDGE to CapabilityRating.MEDIUM,
                    CapabilityDomain.IMAGE_MULTIMODAL to CapabilityRating.DEFICIENT,
                    CapabilityDomain.LONG_TEXT_PROCESSING to CapabilityRating.WEAK
                )
            ),
            ModelCapabilityProfile(
                modelId = "phi4_1_5b",
                modelName = "Phi-4 1.5B",
                ratings = mapOf(
                    CapabilityDomain.CODE to CapabilityRating.STRONG,
                    CapabilityDomain.MATH_LOGIC to CapabilityRating.STRONG,
                    CapabilityDomain.CHINESE_WRITING to CapabilityRating.MEDIUM,
                    CapabilityDomain.MULTILINGUAL_TRANSLATION to CapabilityRating.MEDIUM,
                    CapabilityDomain.PROFESSIONAL_KNOWLEDGE to CapabilityRating.MEDIUM,
                    CapabilityDomain.IMAGE_MULTIMODAL to CapabilityRating.DEFICIENT,
                    CapabilityDomain.LONG_TEXT_PROCESSING to CapabilityRating.MEDIUM
                )
            )
        )
        registerProfiles(profiles)
    }

    private fun String.containsAny(vararg keywords: String): Boolean {
        return keywords.any { this.contains(it, ignoreCase = true) }
    }

    private val CapabilityDomain.displayName: String
        get() = when (this) {
            CapabilityDomain.CODE -> "代码"
            CapabilityDomain.MATH_LOGIC -> "数学与逻辑"
            CapabilityDomain.CHINESE_WRITING -> "中文写作"
            CapabilityDomain.MULTILINGUAL_TRANSLATION -> "多语言翻译"
            CapabilityDomain.PROFESSIONAL_KNOWLEDGE -> "专业知识"
            CapabilityDomain.IMAGE_MULTIMODAL -> "图像/多模态"
            CapabilityDomain.LONG_TEXT_PROCESSING -> "长文本处理"
        }

    private val CapabilityRating.label: String
        get() = when (this) {
            CapabilityRating.STRONG -> "强"
            CapabilityRating.MEDIUM -> "中等"
            CapabilityRating.WEAK -> "较弱"
            CapabilityRating.DEFICIENT -> "存在缺陷"
        }
}
