package com.jarvis.pineapple.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 模型调度器。
 * 优先级：用户模型 > 导入本地 / LiteRT > API 模型 > 服务器模型
 */
class ModelScheduler(
    private val userModel: AiModel,
    private val liteRtModels: List<AiModel> = emptyList()
) {
    private val _availableModels = MutableStateFlow<List<AiModel>>(emptyList())
    val availableModels: StateFlow<List<AiModel>> = _availableModels.asStateFlow()

    private val _activeModel = MutableStateFlow(userModel)
    val activeModel: StateFlow<AiModel> = _activeModel.asStateFlow()

    private val apiModels = mutableListOf<AiModel>()
    private val serverModels = mutableListOf<AiModel>()
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

    fun addApiModel(model: AiModel) {
        apiModels.add(model)
        refresh()
    }

    fun addImportedLocalModel(model: AiModel) {
        importedLocalModels.add(model)
        refresh()
    }

    fun addServerModel(model: AiModel) {
        serverModels.add(model)
        refresh()
    }

    fun selectModel(modelId: String) {
        _availableModels.value.firstOrNull { it.info.id == modelId }?.let {
            _activeModel.value = it
        }
    }

    fun getActive(): AiModel = _activeModel.value

    fun rankedModels(): List<AiModel> = _availableModels.value

    fun isUsable(model: AiModel): Boolean = model.state == ModelState.READY

    /**
     * 按优先级加载第一个可用模型。当前选中模型优先；失败则回退。
     * 全部不可用时返回 null，调用方必须向用户展示 [NO_MODEL_MESSAGE]。
     */
    suspend fun resolveOrLoad(): AiModel? {
        val preferred = listOf(_activeModel.value) + rankedModels().filter { it.info.id != _activeModel.value.info.id }
        for (model in preferred) {
            if (model.state != ModelState.READY) {
                model.load()
            }
            if (isUsable(model)) {
                _activeModel.value = model
                return model
            }
        }
        return null
    }

    companion object {
        const val NO_MODEL_MESSAGE =
            "无可用模型：未检测到已加载的本地模型，也未配置可用的 API 或服务器模型。请先导入本地模型文件，或在设置中填写 API/服务器地址。"
    }
}
