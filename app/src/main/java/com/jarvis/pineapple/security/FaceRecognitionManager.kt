package com.jarvis.pineapple.security

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.core.graphics.scale
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * 人脸识别管理器（功能 #34, #35）。
 *
 * 采用 ML Kit Face Detection 定位人脸区域，再提取 LBP 纹理直方图（256 维）
 * 与灰度网格（64 维）特征向量进行余弦相似度比对。人脸模板持久化到应用私有目录。
 */
class FaceRecognitionManager(private val context: Context) {

    private val _recognizedFaces = MutableStateFlow<List<FaceTemplate>>(emptyList())
    val recognizedFaces: StateFlow<List<FaceTemplate>> = _recognizedFaces.asStateFlow()

    private val storeFile = File(context.filesDir, "faces.json")

    init {
        _recognizedFaces.value = loadFaces()
    }

    /**
     * 人脸模板
     */
    data class FaceTemplate(
        val id: String,
        val name: String,
        val features: FloatArray,
        val createdAt: Long = System.currentTimeMillis()
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FaceTemplate) return false
            return id == other.id
        }

        override fun hashCode(): Int = id.hashCode()
    }

    /**
     * 识别结果
     */
    data class RecognitionResult(
        val isRecognized: Boolean,
        val matchedFace: FaceTemplate?,
        val confidence: Float,
        val allMatches: List<FaceMatch> = emptyList()
    )

    data class FaceMatch(
        val template: FaceTemplate,
        val similarity: Float
    )

    /**
     * 注册人脸
     */
    fun registerFace(name: String, features: FloatArray): FaceTemplate {
        val template = FaceTemplate(
            id = "face_${System.currentTimeMillis()}",
            name = name,
            features = features
        )
        _recognizedFaces.value = _recognizedFaces.value + template
        saveFaces()
        return template
    }

    /**
     * 识别人脸
     */
    fun recognizeFace(features: FloatArray): RecognitionResult {
        val templates = _recognizedFaces.value
        if (templates.isEmpty()) {
            return RecognitionResult(isRecognized = false, matchedFace = null, confidence = 0f)
        }

        val matches = templates.map { template ->
            FaceMatch(template = template, similarity = calculateCosineSimilarity(features, template.features))
        }.sortedByDescending { it.similarity }

        val bestMatch = matches.first()
        val isRecognized = bestMatch.similarity > SIMILARITY_THRESHOLD

        return RecognitionResult(
            isRecognized = isRecognized,
            matchedFace = if (isRecognized) bestMatch.template else null,
            confidence = bestMatch.similarity,
            allMatches = matches.take(3)
        )
    }

    fun deleteFace(faceId: String) {
        _recognizedFaces.value = _recognizedFaces.value.filterNot { it.id == faceId }
        saveFaces()
    }

    fun getAllFaces(): List<FaceTemplate> = _recognizedFaces.value

    private fun calculateCosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator > 0) dotProduct / denominator else 0f
    }

    /**
     * 提取特征向量（自动定位并裁剪人脸）。
     */
    suspend fun extractFeatures(bitmap: Bitmap): FloatArray = withContext(Dispatchers.Default) {
        val box = detectLargestFace(bitmap)
        val cropped = if (box != null) cropFace(bitmap, box) else bitmap
        computeFeatures(cropped)
    }

    private fun detectLargestFace(bitmap: Bitmap): Rect? {
        return try {
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.1f)
                .build()
            val detector = FaceDetection.getClient(options)
            val faces = try {
                Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)), 5, TimeUnit.SECONDS)
            } finally {
                detector.close()
            }
            faces.maxByOrNull { it.boundingBox.width().toLong() * it.boundingBox.height() }?.boundingBox
        } catch (e: Exception) {
            null
        }
    }

    private fun cropFace(bitmap: Bitmap, box: Rect): Bitmap {
        val padding = (box.width() * 0.15f).toInt()
        val left = (box.left - padding).coerceAtLeast(0)
        val top = (box.top - padding).coerceAtLeast(0)
        val right = (box.right + padding).coerceAtMost(bitmap.width)
        val bottom = (box.bottom + padding).coerceAtMost(bitmap.height)
        return if (right > left && bottom > top) {
            Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        } else {
            bitmap
        }
    }

    /**
     * LBP 纹理直方图（256）+ 灰度网格（CELLS x CELLS）拼接。
     */
    private fun computeFeatures(bitmap: Bitmap): FloatArray {
        val grid = GRID
        val scaled = bitmap.scale(grid, grid)
        val pixels = IntArray(grid * grid)
        scaled.getPixels(pixels, 0, grid, 0, 0, grid, grid)
        val gray = FloatArray(pixels.size) { i ->
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }

        val lbp = FloatArray(256)
        for (y in 1 until grid - 1) {
            for (x in 1 until grid - 1) {
                val center = gray[y * grid + x]
                var code = 0
                if (gray[(y - 1) * grid + (x - 1)] >= center) code = code or 1
                if (gray[(y - 1) * grid + x] >= center) code = code or 2
                if (gray[(y - 1) * grid + (x + 1)] >= center) code = code or 4
                if (gray[y * grid + (x + 1)] >= center) code = code or 8
                if (gray[(y + 1) * grid + (x + 1)] >= center) code = code or 16
                if (gray[(y + 1) * grid + x] >= center) code = code or 32
                if (gray[(y + 1) * grid + (x - 1)] >= center) code = code or 64
                if (gray[y * grid + (x - 1)] >= center) code = code or 128
                lbp[code] += 1f
            }
        }
        normalizeL1(lbp)

        val cells = CELLS
        val cell = grid / cells
        val gridFeatures = FloatArray(cells * cells)
        for (gy in 0 until cells) {
            for (gx in 0 until cells) {
                var sum = 0f
                var count = 0
                for (y in gy * cell until (gy + 1) * cell) {
                    for (x in gx * cell until (gx + 1) * cell) {
                        sum += gray[y * grid + x]
                        count++
                    }
                }
                gridFeatures[gy * cells + gx] = if (count > 0) sum / count else 0f
            }
        }
        normalizeL2(gridFeatures)

        return lbp + gridFeatures
    }

    private fun normalizeL1(v: FloatArray) {
        val sum = v.sumOf { kotlin.math.abs(it.toDouble()) }.toFloat()
        if (sum > 1e-6f) for (i in v.indices) v[i] /= sum
    }

    private fun normalizeL2(v: FloatArray) {
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 1e-6f) for (i in v.indices) v[i] /= norm
    }

    private fun saveFaces() {
        try {
            val arr = JSONArray()
            _recognizedFaces.value.forEach { t ->
                val o = JSONObject()
                o.put("id", t.id)
                o.put("name", t.name)
                o.put("createdAt", t.createdAt)
                val f = JSONArray()
                t.features.forEach { f.put(it.toDouble()) }
                o.put("features", f)
                arr.put(o)
            }
            storeFile.writeText(arr.toString())
        } catch (e: Exception) {
            // 持久化失败时忽略，内存中的模板仍可用
        }
    }

    private fun loadFaces(): List<FaceTemplate> {
        if (!storeFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(storeFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val f = o.getJSONArray("features")
                FaceTemplate(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    features = FloatArray(f.length()) { f.getDouble(it).toFloat() },
                    createdAt = o.getLong("createdAt")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val SIMILARITY_THRESHOLD = 0.82f
        private const val GRID = 32
        private const val CELLS = 8
    }
}
