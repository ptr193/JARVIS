package com.jarvis.pineapple.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 模型调度器。
 * 优先级：用户模型 > 5 个 LiteRT > API 模型 > 服务器模型
 * 由用户选择当前激活模型，未选择时默认用户自有模型。
 */
class ModelScheduler(
    private val userModel: UserLocalModel,
    private val liteRtModels: List<LiteRtModel>
) {
    private val _availableModels = MutableStateFlow<List<AiModel>>(emptyList())
    val availableModels: StateFlow<List<AiModel>> = _availableModels.asStateFlow()

    private val _activeModel = MutableStateFlow<AiModel>(userModel)
    val activeModel: StateFlow<AiModel> = _activeModel.asStateFlow()

    private val apiModels = mutableListOf<ApiModel>()
    private val serverModels = mutableListOf<ServerModel>()

    init {
        refresh()
    }

    private fun refresh() {
        // 按优先级排序：用户模型 > LiteRT > API > 服务器
        val all = buildList {
            add(userModel)
            addAll(liteRtModels)
            addAll(apiModels)
            addAll(serverModels)
        }.sortedByDescending { it.info.priority }
        _availableModels.value = all
    }

    fun addApiModel(model: ApiModel) {
        apiModels.add(model)
        refresh()
    }

    fun addServerModel(model: ServerModel) {
        serverModels.add(model)
        refresh()
    }

    fun selectModel(modelId: String) {
        _availableModels.value.firstOrNull { it.info.id == modelId }?.let {
            _activeModel.value = it
        }
    }

    /**
     * 获取当前激活模型；若未就绪则返回默认用户模型。
     */
    fun getActive(): AiModel = _activeModel.value
}
