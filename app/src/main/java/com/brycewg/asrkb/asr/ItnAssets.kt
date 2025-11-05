package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Log

/**
 * 将 assets/itn 下的 FST 规则拷贝到内部存储 files/itn 并返回主规则文件的绝对路径。
 * 约定优先 itn_zh_number.fst；若不存在则取目录下任意 .fst。
 */
object ItnAssets {
  private const val TAG = "ItnAssets"
  private const val ASSETS_DIR = "itn"
  private const val PREFERRED_FILE = "itn_zh_number.fst"

  fun ensureItnFstPath(context: Context): String? {
    return try {
      val am = context.assets
      val names = try { am.list(ASSETS_DIR) ?: emptyArray() } catch (t: Throwable) { Log.w(TAG, "list assets failed", t); emptyArray() }
      if (names.isEmpty()) return null
      val candidate = when {
        names.contains(PREFERRED_FILE) -> PREFERRED_FILE
        else -> names.firstOrNull { it.endsWith(".fst", true) }
      } ?: return null

      val outDir = java.io.File(context.filesDir, ASSETS_DIR)
      if (!outDir.exists()) outDir.mkdirs()
      val outFile = java.io.File(outDir, candidate)

      // 若不存在或大小不一致，执行拷贝
      val needCopy = try {
        val assetLen = am.open("$ASSETS_DIR/$candidate").use { it.available() }
        !outFile.exists() || outFile.length().toInt() != assetLen
      } catch (_: Throwable) { true }

      if (needCopy) {
        am.open("$ASSETS_DIR/$candidate").use { ins ->
          outFile.outputStream().use { outs ->
            ins.copyTo(outs)
          }
        }
      }
      outFile.absolutePath
    } catch (t: Throwable) {
      Log.e(TAG, "ensureItnFstPath failed", t)
      null
    }
  }
}

