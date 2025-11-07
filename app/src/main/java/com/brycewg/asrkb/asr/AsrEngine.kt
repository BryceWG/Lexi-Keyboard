package com.brycewg.asrkb.asr

/**
 * ASR（自动语音识别）引擎基础接口
 * 定义了语音识别引擎的基本功能
 */
interface AsrEngine {
    /** 引擎是否正在运行 */
    val isRunning: Boolean

    /** 开始语音识别 */
    fun start()

    /** 停止语音识别 */
    fun stop()
}

/**
 * 流式 ASR 引擎接口
 * 继承自 AsrEngine，增加了流式识别的功能
 */
interface StreamingAsrEngine : AsrEngine {
    /** 流式识别结果监听器 */
    interface Listener {
        /** 接收最终识别结果 */
        fun onFinal(text: String)

        /** 处理识别过程中的错误 */
        fun onError(message: String)

        /** 接收中间结果（可选实现） */
        fun onPartial(text: String) { /* default no-op */ }

        /**
         * 录音阶段结束（例如用户松手或静音自动判停）。
         * 默认空实现；用于让 UI 在上传/识别阶段将麦克风按钮恢复为"就绪"。
         */
        fun onStopped() { /* default no-op */ }

        /**
         * 接收实时音频振幅（用于波形动画）
         * @param amplitude 归一化的振幅值（0.0-1.0）
         */
        fun onAmplitude(amplitude: Float) { /* default no-op */ }
    }
}
