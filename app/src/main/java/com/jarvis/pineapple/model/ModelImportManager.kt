package com.jarvis.pineapple.model

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 多格式本地模型导入管理器（REQ-04）。
 *
 * 支持 GGUF / ONNX / Safetensors / PyTorch / LiteRT-LM 五类格式的探测与导入：
 * 1. SAF 选择文件 → 2. 魔数探测格式（真实二进制解析）→ 3. 资源可行性评估
 * → 4. 复制入库 + SHA-256 校验 → 5. 注册到模型调度器 → 6. 连通性测试（加载+一次推理）
 *
 * 格式支持矩阵：
 * - LiteRT-LM → LiteRT 引擎（内置）
 * - ONNX → ONNX Runtime（内置）
 * - GGUF / Safetensors / PyTorch → 已完成导入、探测、注册与校验；
 *   推理需对应原生引擎（llama.cpp / vLLM），设备不具备时给出明确转换建议
 * - TensorFlow / SavedModel / CoreML → 未实现结构探测与导入，明确拒绝，不能仅凭扩展名注册
 */
class ModelImportManager(private val context: Context) {

    data class ImportResult(
        val success: Boolean,
        val modelId: String? = null,
        val message: String
    )

    private val _importState = MutableStateFlow<ImportResult?>(null)
    val importState: StateFlow<ImportResult?> = _importState.asStateFlow()

    private val modelsDir = File(context.filesDir, "models")

    /**
     * 从 Uri 导入模型文件（SAF）。
     */
    suspend fun importFromUri(uri: Uri, fileName: String): ImportResult =
        withContext(Dispatchers.IO) {
            try {
                val stageDir = File(context.cacheDir, "model_import_${System.currentTimeMillis()}")
                stageDir.mkdirs()
                val staged = File(stageDir, fileName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(staged).use { output -> input.copyTo(output) }
                } ?: return@withContext fail("无法打开所选文件（权限被拒绝或文件不可用）")

                val result = importFromFile(staged)
                if (!result.success) staged.delete()
                stageDir.deleteRecursively()
                result
            } catch (e: Exception) {
                Log.e("ModelImportManager", "importFromUri failed", e)
                fail("导入失败：${e.message}")
            }
        }

    /**
     * 导入已落盘的模型文件。
     */
    suspend fun importFromFile(file: File): ImportResult = withContext(Dispatchers.IO) {
        // 1. 格式探测（真实魔数解析）
        val probe = ModelFormatDetector.probe(file)
        if (probe is FormatProbe.Rejected) {
            return@withContext fail("格式校验未通过：${probe.reason}")
        }
        val detected = probe as FormatProbe.Detected

        // 2. 资源可行性评估
        val freeBytes = context.filesDir.freeSpace
        if (file.length() * 11 / 10 > freeBytes) {
            return@withContext fail(
                "磁盘空间不足：模型 ${file.length() / (1024 * 1024)} MB，" +
                        "可用 ${freeBytes / (1024 * 1024)} MB（含运行开销预留）"
            )
        }

        val modelId = "imported_${detected.format.name.lowercase()}_" +
                file.nameWithoutExtension.lowercase()
                .replace(Regex("[^a-z0-9_]"), "_").take(40) +
                "_" + (System.currentTimeMillis() % 100000)

        // 3. 先计算源文件摘要，再复制到临时目标并复核；不一致的副本绝不注册。
        val sourceSha256 = sha256Of(file)
        if (sourceSha256.isBlank()) return@withContext fail("源文件 SHA-256 计算失败")
        val targetDir = when (detected.format) {
            ModelFormat.ONNX -> File(modelsDir, "onnx/$modelId")
            else -> File(modelsDir, "imported/$modelId")
        }
        if (!targetDir.mkdirs() && !targetDir.isDirectory) {
            return@withContext fail("无法创建模型目录")
        }
        try {
            val targetFile = File(targetDir, if (detected.format == ModelFormat.ONNX) "model.onnx" else file.name)
            val stagedTarget = File(targetDir, "${targetFile.name}.importing")
            file.copyTo(stagedTarget, overwrite = true)
            val targetSha256 = sha256Of(stagedTarget)
            if (targetSha256.isBlank() || targetSha256 != sourceSha256) {
                return@withContext failImport(targetDir, "源文件与目标副本 SHA-256 不一致")
            }
            if (!stagedTarget.renameTo(targetFile)) {
                return@withContext failImport(targetDir, "模型副本原子落库失败")
            }
            if (detected.format == ModelFormat.ONNX) {
                val tokenizer = File(file.parentFile, "tokenizer.json")
                if (tokenizer.isFile) tokenizer.copyTo(File(targetDir, "tokenizer.json"), overwrite = true)
            }

            // 4. 可推理格式必须先通过真实引擎加载与一次推理，再注册到调度器。
            val scheduler = ModelRegistry.scheduler
                ?: return@withContext failImport(targetDir, "模型调度器未初始化")
            val sha256 = targetSha256
            val info = ModelInfo(
                id = modelId,
                name = file.nameWithoutExtension,
                source = ModelSource.LOCAL_USER,
                description = "${detected.detail}\nSHA-256: $sha256",
                priority = 90,
                maxTokens = 2048
            )

            when (detected.format) {
                ModelFormat.LITERTLM -> {
                    val validationModel = ImportedLiteRtModel(
                        context,
                        info.copy(maxTokens = 32),
                        targetFile
                    )
                    validateInference(validationModel)?.let { error ->
                        return@withContext failImport(targetDir, "LiteRT-LM 验证失败：$error")
                    }
                    scheduler.addImportedLocalModel(ImportedLiteRtModel(context, info, targetFile))
                }
                ModelFormat.ONNX -> {
                    val validationModel = OnnxLocalModel(info.copy(maxTokens = 32), targetDir)
                    validateInference(validationModel)?.let { error ->
                        return@withContext failImport(
                            targetDir,
                            "ONNX 验证失败：$error（需要有效 model.onnx、tokenizer.json 和自回归语言模型结构）"
                        )
                    }
                    scheduler.addImportedLocalModel(OnnxLocalModel(info, targetDir))
                }
                else -> {
                    scheduler.addImportedLocalModel(
                        UnsupportedEngineModel(context, info, detected.format, targetFile)
                    )
                    return@withContext ImportResult(
                        success = true,
                        modelId = modelId,
                        message = "已导入并注册 ${detected.format.label} 文件，但未执行推理验收。\n" +
                            "${detected.detail}\nSHA-256: $sha256\n" +
                            "当前设备没有该格式的推理引擎；文件导入成功不等于模型可对话。"
                    ).also { _importState.value = it }
                }
            }

            ImportResult(
                success = true,
                modelId = modelId,
                message = "已导入并注册 ${detected.format.label} 模型：${file.name}\n" +
                    "${detected.detail}\nSHA-256: $sha256\n真实引擎加载及一次推理已通过。"
            ).also { _importState.value = it }
        } catch (e: Exception) {
            targetDir.deleteRecursively()
            fail("导入失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun failImport(targetDir: File, message: String): ImportResult {
        val cleaned = targetDir.deleteRecursively()
        return fail(if (cleaned) message else "$message；失败副本清理不完整：${targetDir.absolutePath}")
    }

    /** 仅真实引擎实现会进入这里；测试替身不能作为设备推理验收证据。 */
    private suspend fun validateInference(model: AiModel): String? {
        model.load()
        if (model.state != ModelState.READY) {
            model.unload()
            return "真实引擎加载失败"
        }
        return try {
            val output = model.generate("Reply with OK.", null)
            if (output.isBlank() || output.startsWith("[本地模型") || output.startsWith("[ONNX")) {
                "一次真实推理未产生有效输出：${output.ifBlank { "空输出" }.take(300)}"
            } else {
                null
            }
        } catch (e: Exception) {
            "一次真实推理异常：${e.message ?: e.javaClass.simpleName}"
        } finally {
            model.unload()
        }
    }

    private fun fail(message: String): ImportResult {
        val r = ImportResult(success = false, message = message)
        _importState.value = r
        return r
    }

    companion object {
        internal fun sha256Of(file: File): String = try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } > 0) digest.update(buf, 0, n)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }
}

/**
 * 全局调度器注册点（供导入管理器在 DI 之外定位调度器）。
 */
object ModelRegistry {
    @Volatile
    var scheduler: ModelScheduler? = null
}

/**
 * 导入的 LiteRT-LM 模型（可直接推理）。
 */
class ImportedLiteRtModel(
    context: Context,
    info: ModelInfo,
    private val importedFile: File
) : LocalModel(info = info, context = context, modelFileName = importedFile.name) {

    override fun modelFile(): File = importedFile

    override fun toString(): String = "ImportedLiteRtModel(${info.name})"
}

/**
 * 已导入但当前设备缺少推理引擎的模型（如实报告，不伪造推理结果，符合 REQ-05）。
 */
class UnsupportedEngineModel(
    private val context: Context,
    override val info: ModelInfo,
    private val format: ModelFormat,
    private val modelFile: File
) : AiModel {

    override var state: ModelState = ModelState.UNLOADED
        private set

    override suspend fun load() {
        // 文件存在性校验为真实检查；引擎缺失为真实限制
        state = if (modelFile.exists() && modelFile.length() > 0) ModelState.ERROR else ModelState.UNLOADED
    }

    override suspend fun unload() {
        state = ModelState.UNLOADED
    }

    override suspend fun generate(prompt: String, systemPrompt: String?): String =
        "当前设备不具备 ${format.label} 推理引擎。模型文件已导入：" +
                "${modelFile.absolutePath}\n" +
                "建议转换为 ONNX 或 LiteRT-LM 格式后使用。"

    override fun stream(prompt: String, systemPrompt: String?): kotlinx.coroutines.flow.Flow<String> =
        kotlinx.coroutines.flow.flow { emit(generate(prompt, systemPrompt)) }
}
