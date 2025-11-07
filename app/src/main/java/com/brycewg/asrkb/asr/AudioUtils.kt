package com.brycewg.asrkb.asr

/**
 * 振幅非零判定阈值
 *
 * 用于检测音频采样是否包含有效信号。
 * 经验值：环境噪音通常 < 30，正常语音信号 > 100。
 */
private const val AMPLITUDE_NON_ZERO_THRESHOLD = 30

// 更保守的“坏源”判定阈值（近乎全零）。
// 仅用于预热阶段识别 VOICE_RECOGNITION 管线失效的场景；不用于用户是否开口的判断。
private const val BAD_SOURCE_MAX_ABS_THRESHOLD = 12
private const val BAD_SOURCE_RMS_THRESHOLD = 4.0

/**
 * 通用的音频处理工具方法。
 */

@Suppress("unused")
internal fun isLikelyBadSource(maxAbs: Int, rms: Double, countAbove30: Int): Boolean {
    return (maxAbs < BAD_SOURCE_MAX_ABS_THRESHOLD && rms < BAD_SOURCE_RMS_THRESHOLD && countAbove30 == 0)
}

data class FrameStats(
    val maxAbs: Int,
    val sumSquares: Long,
    val countAboveThreshold: Int,
    val sampleCount: Int
)

/**
 * 单次遍历计算帧统计数据（maxAbs、sumSquares、countAboveThreshold）
 */
internal fun computeFrameStats16le(buf: ByteArray, len: Int, threshold: Int = 30): FrameStats {
    var i = 0
    var maxAbs = 0
    var sumSquares = 0L
    var count = 0
    var samples = 0
    while (i + 1 < len) {
        val lo = buf[i].toInt() and 0xFF
        val hi = buf[i + 1].toInt() and 0xFF
        val s = (hi shl 8) or lo
        val v = if (s < 0x8000) s else s - 0x10000
        val a = kotlin.math.abs(v)
        if (a > maxAbs) maxAbs = a
        sumSquares += (v * v).toLong()
        if (a > threshold) count++
        samples++
        i += 2
    }
    return FrameStats(maxAbs, sumSquares, count, samples)
}

/**
 * 计算音频块的归一化振幅（0.0-1.0）用于波形动画
 *
 * 使用 RMS（均方根）算法计算音频能量，并归一化到 [0.0, 1.0] 范围。
 *
 * @param audioChunk PCM 16-bit little-endian 音频数据
 * @return 归一化的振幅值（0.0-1.0）
 */
fun calculateNormalizedAmplitude(audioChunk: ByteArray): Float {
    if (audioChunk.isEmpty()) return 0f

    val stats = computeFrameStats16le(audioChunk, audioChunk.size)
    if (stats.sampleCount == 0) return 0f

    // 计算 RMS
    val rms = kotlin.math.sqrt(stats.sumSquares.toDouble() / stats.sampleCount)

    // 归一化到 0.0-1.0 范围
    // 16-bit PCM 最大值为 32768，RMS 典型范围约 0-10000
    // 使用 3000 作为参考值以获得良好的视觉效果（正常说话音量约 1000-5000）
    val normalized = (rms / 3000.0).coerceIn(0.0, 1.0).toFloat()

    return normalized
}
