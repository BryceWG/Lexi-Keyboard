package com.brycewg.asrkb.ui

import android.content.Context
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor

/**
 * 统一提供 ASR 供应商的顺序与显示名，避免各处硬编码 listOf。
 */
object AsrVendorUi {
  /** 固定的供应商顺序（设置页/菜单统一使用） */
  fun ordered(): List<AsrVendor> = listOf(
    AsrVendor.Volc,
    AsrVendor.SiliconFlow,
    AsrVendor.ElevenLabs,
    AsrVendor.OpenAI,
    AsrVendor.DashScope,
    AsrVendor.Gemini,
    AsrVendor.Soniox,
    AsrVendor.SenseVoice,
    AsrVendor.Paraformer,
    AsrVendor.Zipformer
  )

  /** 指定 vendor 的多语言显示名 */
  fun name(context: Context, v: AsrVendor): String = when (v) {
    AsrVendor.Volc -> context.getString(R.string.vendor_volc)
    AsrVendor.SiliconFlow -> context.getString(R.string.vendor_sf)
    AsrVendor.ElevenLabs -> context.getString(R.string.vendor_eleven)
    AsrVendor.OpenAI -> context.getString(R.string.vendor_openai)
    AsrVendor.DashScope -> context.getString(R.string.vendor_dashscope)
    AsrVendor.Gemini -> context.getString(R.string.vendor_gemini)
    AsrVendor.Soniox -> context.getString(R.string.vendor_soniox)
    AsrVendor.SenseVoice -> context.getString(R.string.vendor_sensevoice)
    AsrVendor.Paraformer -> context.getString(R.string.vendor_paraformer)
    AsrVendor.Zipformer -> context.getString(R.string.vendor_zipformer)
  }

  /** 顺序化的 (Vendor, 显示名) 列表 */
  fun pairs(context: Context): List<Pair<AsrVendor, String>> = ordered().map { it to name(context, it) }

  /** 顺序化的显示名列表 */
  fun names(context: Context): List<String> = ordered().map { name(context, it) }
}

