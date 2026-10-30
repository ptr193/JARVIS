package com.jarvis.pineapple.model

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset

/**
 * ONNX 格式本地模型（REQ-04：端到端 部署→加载→推理）。
 *
 * 推理链：文本 → BPE tokenizer.json → input_ids → ORT session → logits → 贪心解码 → 文本
 * 模型目录约定：models/onnx/<modelId>/ 下放置 model.onnx + tokenizer.json
 */
class OnnxLocalModel(
    override val info: ModelInfo,
    private val modelDir: File
) : AiModel {

    override var state: ModelState = ModelState.UNLOADED
        private set

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tokenizer: BpeTokenizer? = null

    private val modelFile = File(modelDir, "model.onnx")
    private val tokenizerFile = File(modelDir, "tokenizer.json")

    override suspend fun load() {
        state = ModelState.LOADING
        try {
            if (!modelFile.exists() || modelFile.length() == 0L) {
                state = ModelState.ERROR
                return
            }
            if (!tokenizerFile.exists()) {
                state = ModelState.ERROR
                return
            }
            tokenizer = BpeTokenizer.load(tokenizerFile)
            val environment = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            session = environment.createSession(modelFile.absolutePath, opts)
            env = environment
            state = ModelState.READY
        } catch (e: Exception) {
            session = null
            tokenizer = null
            state = ModelState.ERROR
        }
    }

    override suspend fun unload() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        tokenizer = null
        state = ModelState.UNLOADED
    }

    override suspend fun generate(prompt: String, systemPrompt: String?): String {
        val s = session ?: return notReadyMessage()
        val tok = tokenizer ?: return notReadyMessage()
        val environment = env ?: return notReadyMessage()
        return try {
            val fullPrompt = buildString {
                systemPrompt?.let { append(it).append("\n\n") }
                append(prompt)
            }
            val ids = tok.encode(fullPrompt).takeLast(info.maxTokens)
            val outputIds = mutableListOf<Int>().apply { addAll(ids) }
            val eosId = tok.eosTokenId

            var finished = false
            var steps = 0
            while (steps < info.maxTokens) {
                steps++
                if (finished) break
                val idsLong = LongArray(outputIds.size) { outputIds[it].toLong() }
                val inputArray = arrayOf(idsLong)
                val maskArray = arrayOf(LongArray(outputIds.size) { 1L })
                val inputs = buildMap<String, OnnxTensor> {
                    val inputNames = s.inputNames.iterator()
                    val firstName = inputNames.next()
                    put(firstName, OnnxTensor.createTensor(environment, inputArray))
                    if (inputNames.hasNext()) {
                        put(inputNames.next(), OnnxTensor.createTensor(environment, maskArray))
                    }
                }
                s.run(inputs).use { results ->
                    val logits = results[0].value as Array<Array<FloatArray>>
                    val lastLogits = logits[0][logits[0].size - 1]
                    var best = 0
                    var bestVal = Float.NEGATIVE_INFINITY
                    for (i in lastLogits.indices) {
                        if (lastLogits[i] > bestVal) {
                            bestVal = lastLogits[i]
                            best = i
                        }
                    }
                    if (best == eosId) {
                        finished = true
                    } else {
                        outputIds.add(best)
                    }
                }
            }

            val answerIds = outputIds.drop(ids.size)
            if (answerIds.isEmpty()) "[ONNX 模型无输出，请检查模型是否为自回归语言模型]"
            else tok.decode(answerIds)
        } catch (e: Exception) {
            "[ONNX 推理失败] ${e.message}"
        }
    }

    override fun stream(prompt: String, systemPrompt: String?): Flow<String> = flow {
        // ORT 基础 API 无流式回调，逐段推送生成结果
        emit(generate(prompt, systemPrompt))
    }.flowOn(Dispatchers.Default)

    private fun notReadyMessage(): String =
        "ONNX 模型未就绪：${modelFile.absolutePath}\n" +
                "请放置 model.onnx 与 tokenizer.json（HuggingFace 格式）。state=$state"
}

/**
 * 最小可用 BPE 分词器（HuggingFace tokenizer.json：model.vocab + model.merges）。
 */
class BpeTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val merges: Map<Pair<String, String>, Int>,
    val eosTokenId: Int
) {
    fun encode(text: String): List<Int> {
        val ids = mutableListOf<Int>()
        // 以空白切分词，再做 BPE 合并
        val words = Regex("\\S+|\\s+").findAll(text).map { it.value }.toList()
        for (word in words) {
            var symbols = word.map { it.toString() }.toMutableList()
            if (symbols.isEmpty()) continue
            while (symbols.size > 1) {
                var bestPair: Pair<String, String>? = null
                var bestRank = Int.MAX_VALUE
                var bestIndex = -1
                for (i in 0 until symbols.size - 1) {
                    val pair = symbols[i] to symbols[i + 1]
                    val rank = merges[pair] ?: Int.MAX_VALUE
                    if (rank < bestRank) {
                        bestRank = rank
                        bestPair = pair
                        bestIndex = i
                    }
                }
                if (bestPair == null || bestIndex < 0) break
                val merged = symbols.toMutableList()
                merged[bestIndex] = bestPair.first + bestPair.second
                merged.removeAt(bestIndex + 1)
                symbols = merged
            }
            for (sym in symbols) {
                ids.add(vocab[sym] ?: vocab["<unk>"] ?: 0)
            }
        }
        return ids
    }

    fun decode(ids: List<Int>): String {
        val inv = vocab.entries.associate { (k, v) -> v to k }
        val text = ids.joinToString("") { inv[it] ?: "" }
        return text.replace("▁", " ").trim()
    }

    companion object {
        fun load(file: File): BpeTokenizer {
            val root = JSONObject(file.readText(Charset.forName("UTF-8")))
            val model = root.getJSONObject("model")
            val vocabObj = model.getJSONObject("vocab")
            val vocab = mutableMapOf<String, Int>()
            val keys = vocabObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                vocab[k] = vocabObj.getInt(k)
            }
            val mergesArr = model.optJSONArray("merges")
            val merges = mutableMapOf<Pair<String, String>, Int>()
            if (mergesArr != null) {
                for (i in 0 until mergesArr.length()) {
                    val entry = mergesArr.get(i)
                    if (entry is String) {
                        val parts = entry.split(" ", limit = 2)
                        if (parts.size == 2) merges[parts[0] to parts[1]] = i
                    } else if (entry is org.json.JSONArray && entry.length() == 2) {
                        merges[entry.getString(0) to entry.getString(1)] = i
                    }
                }
            }
            val eos = vocab["</s>"] ?: vocab["<|end_of_text|>"] ?: vocab["[SEP]"] ?: -1
            return BpeTokenizer(vocab, merges, eos)
        }
    }
}
