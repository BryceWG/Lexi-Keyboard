package com.brycewg.asrkb.aidl

import android.os.Parcel
import android.os.Parcelable

/**
 * 外部调用的会话配置（AIDL Parcelable）。
 * 为保持向后兼容，新增字段请保持可空并提供合理默认。
 */
data class SpeechConfig(
    val vendorId: String? = null,          // 供应商ID（如 "volc"、"soniox"）；为空则按应用内设置
    val streamingPreferred: Boolean = true,// 调用方偏好流式（若供应商/设置不支持则回落）
    val punctuationEnabled: Boolean? = null,// 标点开关（部分供应商有效）；null=按应用设置
    val autoStopOnSilence: Boolean? = null,// 静音自动判停（null=按应用设置）
    val sessionTag: String? = null         // 调用方自定义标记，用于打点/排障
) : Parcelable {
    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(vendorId)
        dest.writeInt(if (streamingPreferred) 1 else 0)
        writeNullableBoolean(dest, punctuationEnabled)
        writeNullableBoolean(dest, autoStopOnSilence)
        dest.writeString(sessionTag)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<SpeechConfig> {
        override fun createFromParcel(p: Parcel): SpeechConfig {
            val vendor = p.readString()
            val streaming = p.readInt() != 0
            val punct = readNullableBoolean(p)
            val autoSil = readNullableBoolean(p)
            val tag = p.readString()
            return SpeechConfig(vendor, streaming, punct, autoSil, tag)
        }

        override fun newArray(size: Int): Array<SpeechConfig?> = arrayOfNulls(size)

        private fun writeNullableBoolean(dest: Parcel, v: Boolean?) {
            when (v) {
                null -> dest.writeInt(-1)
                true -> dest.writeInt(1)
                false -> dest.writeInt(0)
            }
        }

        private fun readNullableBoolean(p: Parcel): Boolean? = when (val v = p.readInt()) {
            -1 -> null
            0 -> false
            else -> true
        }
    }
}

