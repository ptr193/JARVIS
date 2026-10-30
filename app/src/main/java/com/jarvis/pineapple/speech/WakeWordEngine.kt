package com.jarvis.pineapple.speech

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 唤醒词本地声学引擎（纯 Kotlin 实现，无外部依赖）。
 *
 * 处理链：分帧 → 预加重 → 汉明窗 → FFT → Mel 滤波器组 → 对数 → DCT-II → MFCC
 * 识别链：VAD → 端点检测 → 倒谱均值归一化 → DTW 模板匹配。
 *
 * 全部计算在本机完成，音频不上传云端（REQ-10 隐私要求）。
 */
object WakeWordEngine {

    const val SAMPLE_RATE = 16000
    const val FRAME_LEN = 400      // 25ms
    const val HOP = 160            // 10ms
    const val MEL_BANDS = 26
    const val CEPSTRUM = 13

    // ==================== MFCC ====================

    /** 计算单帧 13 维 MFCC。 */
    fun mfcc(frame: ShortArray): FloatArray {
        val n = frame.size
        // 预加重
        val pre = DoubleArray(n)
        var prev = 0.0
        for (i in 0 until n) {
            val cur = frame[i].toDouble()
            pre[i] = cur - 0.97 * prev
            prev = cur
        }
        // 汉明窗
        val windowed = DoubleArray(n)
        for (i in 0 until n) {
            val w = 0.54 - 0.46 * cos(2.0 * PI * i / (n - 1).coerceAtLeast(1))
            windowed[i] = pre[i] * w
        }
        // FFT
        val fftSize = nextPow2(max(n, 256))
        val re = DoubleArray(fftSize)
        val im = DoubleArray(fftSize)
        for (i in 0 until n) re[i] = windowed[i]
        fft(re, im)

        val half = fftSize / 2 + 1
        val power = DoubleArray(half)
        for (i in 0 until half) power[i] = (re[i] * re[i] + im[i] * im[i]) / fftSize

        // Mel 滤波器组
        val filters = melFilterBank(half, SAMPLE_RATE, MEL_BANDS)
        val melEnergies = DoubleArray(MEL_BANDS)
        for (m in 0 until MEL_BANDS) {
            var sum = 0.0
            val f = filters[m]
            for (k in 0 until half) sum += f[k] * power[k]
            melEnergies[m] = ln(sum.coerceAtLeast(1e-10))
        }
        // DCT-II → 倒谱
        val cep = FloatArray(CEPSTRUM)
        for (i in 0 until CEPSTRUM) {
            var sum = 0.0
            for (m in 0 until MEL_BANDS) {
                sum += melEnergies[m] * cos(PI * i * (m + 0.5) / MEL_BANDS)
            }
            cep[i] = sum.toFloat()
        }
        return cep
    }

    /** 整段音频的 MFCC 序列。 */
    fun mfccSequence(samples: ShortArray): Array<FloatArray> {
        if (samples.size < FRAME_LEN) return emptyArray()
        val frames = ArrayList<FloatArray>()
        var pos = 0
        while (pos + FRAME_LEN <= samples.size) {
            frames.add(mfcc(samples.copyOfRange(pos, pos + FRAME_LEN)))
            pos += HOP
        }
        return frames.toTypedArray()
    }

    /** 倒谱均值归一化，抑制信道/音量差异。 */
    fun cmn(seq: Array<FloatArray>): Array<FloatArray> {
        if (seq.isEmpty()) return seq
        val dim = seq[0].size
        val mean = DoubleArray(dim)
        for (f in seq) for (d in 0 until dim) mean[d] += f[d]
        for (d in 0 until dim) mean[d] /= seq.size
        return Array(seq.size) { i -> FloatArray(dim) { d -> (seq[i][d] - mean[d]).toFloat() } }
    }

    // ==================== DTW ====================

    /**
     * 归一化 DTW 距离（越小越相似）。加入 Sakoe-Chiba 带宽约束。
     */
    fun dtw(a: Array<FloatArray>, b: Array<FloatArray>): Float {
        if (a.isEmpty() || b.isEmpty()) return Float.MAX_VALUE
        val n = a.size
        val m = b.size
        val band = max(8, (max(n, m) / 4))
        val inf = Float.MAX_VALUE / 4f
        var prev = FloatArray(m + 1) { inf }
        var cur = FloatArray(m + 1) { inf }
        prev[0] = 0f
        for (i in 1..n) {
            cur.fill(inf)
            val lo = max(1, i - band)
            val hi = min(m, i + band)
            for (j in lo..hi) {
                val cost = euclid(a[i - 1], b[j - 1])
                val best = minOf(prev[j], prev[j - 1], cur[j - 1])
                cur[j] = cost + best
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        val path = (n + m).toFloat()
        return prev[m] / path
    }

    private fun euclid(x: FloatArray, y: FloatArray): Float {
        var s = 0f
        val d = min(x.size, y.size)
        for (i in 0 until d) {
            val diff = x[i] - y[i]
            s += diff * diff
        }
        return sqrt(s)
    }

    // ==================== VAD ====================

    /** 自适应能量语音活动检测。 */
    class Vad {
        private var noiseFloor = 250.0
        private val alpha = 0.95

        fun isSpeech(frame: ShortArray): Boolean {
            var sum = 0.0
            for (s in frame) sum += s.toDouble() * s
            val rms = sqrt(sum / frame.size.coerceAtLeast(1))
            if (rms < noiseFloor * 1.5) {
                noiseFloor = alpha * noiseFloor + (1 - alpha) * rms
            }
            return rms > noiseFloor * 2.0 + 120.0
        }

        fun reset() { noiseFloor = 250.0 }
        fun noiseFloor(): Double = noiseFloor
    }

    /**
     * 从连续语流中裁剪出有效语音段（端点检测）。
     * 返回去除首尾静音后的样本。
     */
    fun trimSilence(samples: ShortArray, leadingPadMs: Int = 80, trailingPadMs: Int = 120): ShortArray {
        val vad = Vad()
        val n = samples.size
        if (n < FRAME_LEN) return samples
        var first = -1
        var last = -1
        var pos = 0
        while (pos + FRAME_LEN <= n) {
            val frame = samples.copyOfRange(pos, pos + FRAME_LEN)
            if (vad.isSpeech(frame)) {
                if (first < 0) first = pos
                last = pos + FRAME_LEN
            }
            pos += HOP
        }
        if (first < 0) return ShortArray(0)
        val leadPad = SAMPLE_RATE * leadingPadMs / 1000
        val trailPad = SAMPLE_RATE * trailingPadMs / 1000
        val from = max(0, first - leadPad)
        val to = min(n, last + trailPad)
        return samples.copyOfRange(from, to)
    }

    // ==================== FFT ====================

    private fun nextPow2(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** 三角 Mel 滤波器组。 */
    private fun melFilterBank(half: Int, sampleRate: Int, bands: Int): Array<DoubleArray> {
        val minMel = hzToMel(0.0)
        val maxMel = hzToMel(sampleRate / 2.0)
        val melPts = DoubleArray(bands + 2) { minMel + (maxMel - minMel) * it / (bands + 1) }
        val hzPts = DoubleArray(melPts.size) { melToHz(melPts[it]) }
        val bin = IntArray(hzPts.size) { (hzPts[it] / sampleRate * (half - 1)).toInt().coerceIn(0, half - 1) }

        return Array(bands) { m ->
            val f = DoubleArray(half)
            val left = bin[m]
            val center = bin[m + 1]
            val right = bin[m + 2]
            for (k in left until center) {
                if (center > left) f[k] = (k - left).toDouble() / (center - left)
            }
            for (k in center until right) {
                if (right > center) f[k] = (right - k).toDouble() / (right - center)
            }
            if (center in 0 until half) f[center] = 1.0
            f
        }
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)
}
