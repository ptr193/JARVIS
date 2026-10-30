package com.jarvis.pineapple.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 模型调度器。
 * 优先级：用户模型 > 5 个 LiteRT > API 模型 > 服务器模型
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
    private val importedLocalModels = mutableListOf<AiModel>()

    init {
        refresh()
    }

    private fun refresh() {
        val all = buildList {
            add(userModel)
            addAll(importedLocalModels)
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

    /** 注册导入的本地模型（REQ-04）。 */
    fun addImportedLocalModel(model: AiModel) {
        importedLocalModels.add(model)
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

    fun getActive(): AiModel = _activeModel.value
}
