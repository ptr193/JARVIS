package com.jarvis.pineapple.integration

import com.jarvis.pineapple.speech.WakeWordEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * 集成测试：语音唤醒本地声学流水线（REQ-10）。
 *
 * 覆盖「音频采集样本 → VAD 端点检测 → MFCC 特征 → 倒谱均值归一化 → DTW 模板匹配」
 * 的完整链路，验证同一唤醒词可被匹配、不同词被区分。
 */
class WakeWordPipelineIntegrationTest {

    private fun tone(freqHz: Double, ms: Int, amplitude: Double = 12000.0): ShortArray {
        val n = WakeWordEngine.SAMPLE_RATE * ms / 1000
        return ShortArray(n) { i ->
            (amplitude * sin(2.0 * PI * freqHz * i / WakeWordEngine.SAMPLE_RATE)).toInt().toShort()
        }
    }

    private fun silence(ms: Int): ShortArray =
        ShortArray(WakeWordEngine.SAMPLE_RATE * ms / 1000)

    private fun word(vararg freqs: Double): ShortArray {
        val out = mutableListOf<Short>()
        freqs.forEach { f -> tone(f, 120).forEach { out.add(it) } }
        return out.toShortArray()
    }

    private fun features(audio: ShortArray): Array<FloatArray> =
        WakeWordEngine.cmn(WakeWordEngine.mfccSequence(audio))

    @Test
    fun `mfcc sequence is produced for a spoken word`() {
        val seq = WakeWordEngine.mfccSequence(word(400.0, 700.0, 520.0))
        assertTrue("MFCC 序列应非空", seq.isNotEmpty())
        assertTrue("每帧应为 13 维倒谱", seq[0].size == WakeWordEngine.CEPSTRUM)
        assertTrue("短于单帧的音频应无特征", WakeWordEngine.mfccSequence(ShortArray(10)).isEmpty())
    }

    @Test
    fun `vad distinguishes speech from silence`() {
        val vad = WakeWordEngine.Vad()
        assertFalse("静音不应被判为语音", vad.isSpeech(silence(25)))
        assertTrue("能量充足的有声帧应判为语音", vad.isSpeech(tone(440.0, 25)))
    }

    @Test
    fun `endpoint detection trims leading and trailing silence`() {
        val audio = silence(400) + word(500.0, 800.0, 600.0) + silence(500)
        val trimmed = WakeWordEngine.trimSilence(audio)
        assertTrue("应裁出有效语音段", trimmed.isNotEmpty())
        assertTrue("裁剪结果应短于原始音频", trimmed.size < audio.size)
        assertTrue("全静音应裁为空", WakeWordEngine.trimSilence(silence(600)).isEmpty())
    }

    @Test
    fun `dtw matches same word and rejects different word`() {
        val template = features(word(400.0, 700.0, 520.0))
        val sameWord = features(word(400.0, 700.0, 520.0))
        val otherWord = features(word(1300.0, 260.0, 980.0))

        val dSame = WakeWordEngine.dtw(template, sameWord)
        val dOther = WakeWordEngine.dtw(template, otherWord)

        assertTrue("相同词的 DTW 距离应接近 0（实际 $dSame）", dSame < 0.01f)
        assertTrue(
            "相同词距离应显著小于不同词（same=$dSame, other=$dOther）",
            dSame < dOther
        )
    }

    @Test
    fun `empty inputs are handled without crashing`() {
        assertTrue(WakeWordEngine.dtw(emptyArray(), emptyArray()) == Float.MAX_VALUE)
        assertTrue(WakeWordEngine.cmn(emptyArray()).isEmpty())
    }
}
