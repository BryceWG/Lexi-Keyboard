package com.brycewg.asrkb.ui.about

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.content.pm.PackageManager
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.store.Prefs
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.brycewg.asrkb.ui.AsrAccessibilityService
import android.provider.Settings
import android.os.PowerManager
import android.Manifest
import java.text.NumberFormat
import java.util.Locale
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class AboutActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_about)

    // 应用 Window Insets 以适配 Android 15 边缘到边缘显示
    findViewById<android.view.View>(android.R.id.content).let { rootView ->
      com.brycewg.asrkb.ui.WindowInsetsHelper.applySystemBarsInsets(rootView)
    }

    val tvAppName = findViewById<TextView>(R.id.tvAppName)
    val tvVersion = findViewById<TextView>(R.id.tvVersion)
    val tvPackage = findViewById<TextView>(R.id.tvPackage)
    val btnGithub = findViewById<Button>(R.id.btnOpenGithub)

    tvAppName.text = getString(R.string.about_app_name, getString(R.string.app_name))
    val pm = packageManager
    val pInfo = try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
      } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(packageName, 0)
      }
    } catch (_: Exception) { null }

    val versionName = pInfo?.versionName ?: ""
    val versionCodeLong = pInfo?.longVersionCode ?: 0L
    tvVersion.text = getString(R.string.about_version, "$versionName ($versionCodeLong)")
    tvPackage.text = getString(R.string.about_package, packageName)

    btnGithub.setOnClickListener {
      try {
        val url = getString(R.string.about_project_url)
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
      } catch (e: ActivityNotFoundException) {
        Log.e(TAG, "Failed to open GitHub URL: No browser found", e)
        Toast.makeText(this, R.string.error_open_browser, Toast.LENGTH_SHORT).show()
      } catch (e: Throwable) {
        Log.e(TAG, "Failed to open GitHub URL", e)
        Toast.makeText(this, R.string.error_open_browser, Toast.LENGTH_SHORT).show()
      }
    }

    // 调试日志控制：开始/停止、导出分享
    val btnToggle = findViewById<Button>(R.id.btnToggleDebugRecording)
    val btnExport = findViewById<Button>(R.id.btnExportDebugLog)
    updateToggleText(btnToggle)
    btnToggle.setOnClickListener {
      try {
        if (DebugLogManager.isRecording()) {
          DebugLogManager.stop()
          Toast.makeText(this, R.string.toast_debug_recording_stopped, Toast.LENGTH_SHORT).show()
        } else {
          DebugLogManager.start(this)
          Toast.makeText(this, R.string.toast_debug_recording_started, Toast.LENGTH_SHORT).show()
          try {
            val overlayOk = try { Settings.canDrawOverlays(this) } catch (_: Throwable) { false }
            val pm = try { getSystemService(PowerManager::class.java) } catch (_: Throwable) { null }
            val batteryIgnore = try { pm?.isIgnoringBatteryOptimizations(packageName) ?: false } catch (_: Throwable) { false }
            val notifGranted = try {
              if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
              } else true
            } catch (_: Throwable) { null } ?: true
            DebugLogManager.log(
              category = "env",
              event = "permissions",
              data = mapOf(
                "overlay" to overlayOk,
                "a11y" to AsrAccessibilityService.isEnabled(),
                "batteryIgnore" to batteryIgnore,
                "notifGranted" to notifGranted
              )
            )
          } catch (e: Throwable) {
            Log.w(TAG, "Failed to log env status", e)
          }
        }
        updateToggleText(btnToggle)
      } catch (t: Throwable) {
        Log.e(TAG, "Failed toggling debug recording", t)
        Toast.makeText(this, R.string.toast_debug_failed, Toast.LENGTH_SHORT).show()
      }
    }
    btnExport.setOnClickListener {
      try {
        val result = DebugLogManager.buildShareIntent(this)
        when (result) {
          is DebugLogManager.ShareIntentResult.Success -> {
            try {
              startActivity(Intent.createChooser(result.intent, getString(R.string.btn_debug_export)))
            } catch (e: Throwable) {
              Log.e(TAG, "Failed to start share chooser", e)
              Toast.makeText(this, R.string.toast_debug_export_failed, Toast.LENGTH_SHORT).show()
            }
          }
          is DebugLogManager.ShareIntentResult.Error -> {
            when (result.error) {
              DebugLogManager.ShareError.RecordingActive -> Toast.makeText(this, R.string.toast_debug_stop_before_export, Toast.LENGTH_SHORT).show()
              DebugLogManager.ShareError.NoLog -> Toast.makeText(this, R.string.toast_debug_no_log, Toast.LENGTH_SHORT).show()
              DebugLogManager.ShareError.Failed -> Toast.makeText(this, R.string.toast_debug_export_failed, Toast.LENGTH_SHORT).show()
            }
          }
        }
      } catch (t: Throwable) {
        Log.e(TAG, "Failed to export debug log", t)
        Toast.makeText(this, R.string.toast_debug_export_failed, Toast.LENGTH_SHORT).show()
      }
    }

    // 返回箭头点击关闭（若布局中设置了导航图标）
    findViewById<androidx.appcompat.widget.Toolbar?>(R.id.toolbar)?.setNavigationOnClickListener {
      finish()
    }

    // 渲染使用统计
    try {
      renderUsageStats()
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to render usage stats", t)
    }
  }

  companion object {
    private const val TAG = "AboutActivity"
  }

  private fun renderUsageStats() {
    val prefs = Prefs(this)
    val stats = prefs.getUsageStats()

    val tvDays = findViewById<TextView>(R.id.tvDaysWithYou)
    val tvSum1 = findViewById<TextView>(R.id.tvSummaryLine1)
    val tvSum2 = findViewById<TextView>(R.id.tvSummaryLine2)
    val tvAvg = findViewById<TextView>(R.id.tvDailyWeeklyAvg)
    val vendorContainer = findViewById<android.widget.LinearLayout>(R.id.containerVendorBars)
    val last7Container = findViewById<android.widget.LinearLayout>(R.id.containerLast7)

    // 陪伴天数
    val daysWithYou = prefs.getDaysSinceFirstUse()
    tvDays.text = getString(R.string.about_days_with_you, daysWithYou)

    val sessions = stats.totalSessions.coerceAtLeast(0)
    val totalChars = stats.totalChars.coerceAtLeast(0)
    val totalAudioMs = stats.totalAudioMs.coerceAtLeast(0)

    val totalAudioStr = formatDurationMs(totalAudioMs)
    tvSum1.text = getString(R.string.about_total_audio, totalAudioStr)

    if (sessions > 0) {
      val avgAudio = totalAudioMs / sessions
      val avgChars = totalChars / sessions
      val avgSpeed = if (totalAudioMs > 0) totalChars * 60_000.0 / totalAudioMs.toDouble() else 0.0
      tvSum2.text = getString(
        R.string.about_avg_line,
        formatDurationMs(avgAudio),
        avgChars,
        String.format(Locale.getDefault(), "%.1f", avgSpeed)
      )
    } else {
      tvSum2.text = getString(R.string.about_empty_stats_placeholder)
    }

    // 每日/每周平均（近 7 天 / 近 4 周 = 28 天）
    val daily7 = sumDaily(stats, 7)
    val weekly4Chars = daily(stats, 28).second / 4 // 字/周（取整）
    val weekly4AudioMs = daily(stats, 28).first / 4
    tvAvg.text = getString(
      R.string.about_daily_weekly_avg,
      (daily7.second / 7),
      formatDurationMs(daily7.first / 7),
      weekly4Chars,
      formatDurationMs(weekly4AudioMs)
    )

    // 供应商条形图（按字符数）
    vendorContainer.removeAllViews()
    val vendorPairs = stats.perVendor.map { it.key to it.value }.sortedByDescending { it.second.chars }
    val maxChars = vendorPairs.maxOfOrNull { it.second.chars } ?: 0L
    if (vendorPairs.isEmpty() || maxChars <= 0) {
      addInfoLine(vendorContainer, getString(R.string.about_empty_stats_placeholder))
    } else {
      vendorPairs.forEach { (id, agg) ->
        val name = vendorDisplayName(AsrVendor.fromId(id))
        val base = StringBuilder()
          .append(name)
          .append("：")
          .append(formatInt(agg.chars)).append(" ").append(getString(R.string.unit_chars))
          .append(" / ")
          .append(formatDurationMs(agg.audioMs))
        if (agg.procMs > 0) {
          base.append(" / ")
            .append(getString(R.string.about_proc_prefix)).append(" ")
            .append(formatDurationMs(agg.procMs))
        }
        addInfoLine(vendorContainer, base.toString())
        addProgress(vendorContainer, agg.chars.toDouble() / maxChars.toDouble())
      }
    }

    // 最近 7 天 / 30 天（按字数）
    renderDailyBars(last7Container, stats, 7)
  }

  private fun vendorDisplayName(v: AsrVendor): String = when (v) {
    AsrVendor.Volc -> getString(R.string.vendor_volc)
    AsrVendor.SiliconFlow -> getString(R.string.vendor_sf)
    AsrVendor.ElevenLabs -> getString(R.string.vendor_eleven)
    AsrVendor.OpenAI -> getString(R.string.vendor_openai)
    AsrVendor.DashScope -> getString(R.string.vendor_dashscope)
    AsrVendor.Gemini -> getString(R.string.vendor_gemini)
    AsrVendor.Soniox -> getString(R.string.vendor_soniox)
    AsrVendor.SenseVoice -> getString(R.string.vendor_sensevoice)
  }

  private fun addInfoLine(container: android.view.ViewGroup, text: String) {
    val tv = TextView(this)
    tv.setTextColor(com.google.android.material.color.MaterialColors.getColor(tv, com.google.android.material.R.attr.colorOnSurfaceVariant))
    tv.textSize = 14f
    tv.text = text
    container.addView(tv)
  }

  private fun addProgress(container: android.view.ViewGroup, ratio: Double) {
    val p = LinearProgressIndicator(this)
    p.isIndeterminate = false
    p.max = 1000
    val prog = (ratio.coerceIn(0.0, 1.0) * 1000).toInt()
    p.progress = prog
    val lp = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (4 * resources.displayMetrics.density).toInt())
    lp.topMargin = (4 * resources.displayMetrics.density).toInt()
    lp.bottomMargin = (8 * resources.displayMetrics.density).toInt()
    container.addView(p, lp)
  }

  private fun sumDaily(stats: Prefs.UsageStats, days: Int): Pair<Long, Long> {
    val (audioMs, chars) = daily(stats, days)
    return audioMs to chars
  }

  private fun daily(stats: Prefs.UsageStats, days: Int): Pair<Long, Long> {
    val fmt = DateTimeFormatter.BASIC_ISO_DATE
    var sumAudio = 0L
    var sumChars = 0L
    var d = LocalDate.now()
    repeat(days) {
      val key = d.format(fmt)
      val a = stats.daily[key]
      if (a != null) {
        sumAudio += a.audioMs
        sumChars += a.chars
      }
      d = d.minusDays(1)
    }
    return sumAudio to sumChars
  }

  private fun renderDailyBars(container: android.widget.LinearLayout, stats: Prefs.UsageStats, days: Int) {
    container.removeAllViews()
    val fmt = DateTimeFormatter.BASIC_ISO_DATE
    val labelFmt = DateTimeFormatter.ofPattern("MM-dd")
    val values = ArrayList<Pair<String, Long>>()
    var d = LocalDate.now()
    repeat(days) {
      val key = d.format(fmt)
      val label = d.format(labelFmt)
      val v = stats.daily[key]?.chars ?: 0L
      values.add(label to v)
      d = d.minusDays(1)
    }
    values.reverse() // 从旧到新
    val max = values.maxOfOrNull { it.second } ?: 0L
    if (max == 0L) {
      addInfoLine(container, getString(R.string.about_empty_stats_placeholder))
      return
    }
    values.forEach { (label, v) ->
      addInfoLine(container, "$label  ${formatInt(v)}${getString(R.string.unit_chars)}")
      addProgress(container, v.toDouble() / max.toDouble())
    }
  }

  private fun formatDurationMs(ms: Long): String {
    if (ms <= 0) return getString(R.string.unit_0_min)
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    val hr = min / 60
    return when {
      hr > 0 -> getString(R.string.fmt_h_m, hr, (min % 60))
      min > 0 -> getString(R.string.fmt_m_s, min, sec)
      else -> getString(R.string.fmt_s, sec)
    }
  }

  private fun formatInt(v: Long): String = NumberFormat.getIntegerInstance().format(v)

  private fun updateToggleText(btn: Button) {
    val textRes = if (DebugLogManager.isRecording()) R.string.btn_debug_stop_recording else R.string.btn_debug_start_recording
    btn.setText(textRes)
  }
}
