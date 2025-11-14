package com.brycewg.asrkb.clipboard

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.store.Prefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 剪贴板条目类型
 */
@Serializable
enum class EntryType {
    TEXT, IMAGE, FILE
}

/**
 * 下载状态
 */
@Serializable
enum class DownloadStatus {
    NONE,        // 未下载
    DOWNLOADING, // 下载中
    COMPLETED,   // 已完成
    FAILED       // 失败
}

/**
 * 简单的剪贴板历史存储：
 * - 按时间倒序（最新在前）
 * - 支持固定（置顶）与普通记录分开存储
 * - 仅固定记录参与备份（存储于 KEY_CLIP_PINNED_JSON）
 * - 普通记录存储于 KEY_CLIP_HISTORY_JSON，不参与备份
 * - 支持文本和文件类型
 */
class ClipboardHistoryStore(private val context: Context, private val prefs: Prefs) {

    @Serializable
    data class Entry(
        val id: String,
        val text: String = "",              // 文本内容（保持向后兼容）
        val ts: Long,
        val pinned: Boolean,
        // 新增字段：支持文件类型
        val type: EntryType = EntryType.TEXT,
        val fileName: String? = null,       // 文件名
        val fileSize: Long? = null,         // 文件大小（字节）
        val mimeType: String? = null,       // MIME类型
        val localFilePath: String? = null,  // 本地文件路径
        val downloadStatus: DownloadStatus = DownloadStatus.NONE,
        val serverFileName: String? = null  // 服务器上的文件名（用于下载）
    )

    private val sp by lazy { context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE) }
    private val json by lazy { Json { ignoreUnknownKeys = true; encodeDefaults = true } }

    companion object {
        private const val TAG = "ClipboardHistoryStore"
        private const val MAX_HISTORY = 200
        private const val MAX_PINNED = 200
        private const val KEY_CLIP_HISTORY_JSON = "clip_history"
        private const val KEY_CLIP_PINNED_JSON = "clip_pinned"
    }

    fun getAll(): List<Entry> {
        return getPinned() + getHistory()
    }

    fun getPinned(): List<Entry> {
        return try {
            val s = sp.getString(KEY_CLIP_PINNED_JSON, null) ?: return emptyList()
            json.decodeFromString<List<Entry>>(s).sortedByDescending { it.ts }.filter { it.pinned }
        } catch (t: Throwable) {
            Log.w(TAG, "parse pinned failed", t)
            emptyList()
        }
    }

    fun getHistory(): List<Entry> {
        return try {
            val s = sp.getString(KEY_CLIP_HISTORY_JSON, null) ?: return emptyList()
            json.decodeFromString<List<Entry>>(s).sortedByDescending { it.ts }.filter { !it.pinned }
        } catch (t: Throwable) {
            Log.w(TAG, "parse history failed", t)
            emptyList()
        }
    }

    fun totalCount(): Int = getPinned().size + getHistory().size

    /**
     * 将当前剪贴板文本追加到历史（作为非固定项）。
     * 若与最新的一条非固定记录相同，则跳过。
     */
    fun addFromClipboard(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        try {
            val his = getHistory().toMutableList()
            if (his.firstOrNull()?.text == trimmed) return
            his.add(0, Entry(UUID.randomUUID().toString(), trimmed, System.currentTimeMillis(), pinned = false))
            while (his.size > MAX_HISTORY) if (his.isNotEmpty()) his.removeAt(his.lastIndex) else break
            sp.edit().putString(KEY_CLIP_HISTORY_JSON, json.encodeToString(his)).apply()
        } catch (t: Throwable) {
            Log.e(TAG, "addFromClipboard failed", t)
        }
    }

    /**
     * 切换固定状态（根据 id）。返回新的固定状态。
     * 若该条在历史中，则移入/移出固定集合；保持两个集合各自有序（按时间倒序）。
     */
    fun togglePin(id: String): Boolean {
        val pinned = getPinned().toMutableList()
        val history = getHistory().toMutableList()
        // 先在历史里找
        val idxH = history.indexOfFirst { it.id == id }
        if (idxH >= 0) {
            val e = history.removeAt(idxH)
            val pe = e.copy(pinned = true, ts = System.currentTimeMillis())
            pinned.add(0, pe)
            while (pinned.size > MAX_PINNED) if (pinned.isNotEmpty()) pinned.removeAt(pinned.lastIndex) else break
            persist(pinned, history)
            return true
        }
        // 再在固定里找 -> 取消固定
        val idxP = pinned.indexOfFirst { it.id == id }
        if (idxP >= 0) {
            val e = pinned.removeAt(idxP)
            val he = e.copy(pinned = false, ts = System.currentTimeMillis())
            history.add(0, he)
            while (history.size > MAX_HISTORY) if (history.isNotEmpty()) history.removeAt(history.lastIndex) else break
            persist(pinned, history)
            return false
        }
        return false
    }

    /** 删除指定时间之前的非固定记录（不影响固定）。返回删除数量。 */
    fun deleteHistoryBefore(cutoffEpochMs: Long): Int {
        val history = getHistory().toMutableList()
        val beforeSize = history.size
        val remain = history.filter { it.ts >= cutoffEpochMs }
        sp.edit().putString(KEY_CLIP_HISTORY_JSON, json.encodeToString(remain)).apply()
        return beforeSize - remain.size
    }

    /** 清空所有非固定记录 */
    fun clearAllNonPinned(): Int {
        val sz = getHistory().size
        sp.edit().remove(KEY_CLIP_HISTORY_JSON).apply()
        return sz
    }

    private fun persist(pinned: List<Entry>, history: List<Entry>) {
        try {
            sp.edit()
                .putString(KEY_CLIP_PINNED_JSON, json.encodeToString(pinned.sortedByDescending { it.ts }))
                .putString(KEY_CLIP_HISTORY_JSON, json.encodeToString(history.sortedByDescending { it.ts }))
                .apply()
        } catch (t: Throwable) {
            Log.e(TAG, "persist failed", t)
        }
    }

    /** 从非固定历史中删除指定ID的记录 */
    fun deleteHistoryById(id: String): Boolean {
        return try {
            val history = getHistory().toMutableList()
            val idx = history.indexOfFirst { it.id == id }
            if (idx >= 0) {
                history.removeAt(idx)
                sp.edit().putString(KEY_CLIP_HISTORY_JSON, json.encodeToString(history)).apply()
                true
            } else false
        } catch (t: Throwable) {
            Log.e(TAG, "deleteHistoryById failed", t)
            false
        }
    }

    /**
     * 粘贴到目标输入框
     */
    fun pasteInto(ic: android.view.inputmethod.InputConnection?, text: String) {
        if (ic == null) return
        try {
            ic.commitText(text, 1)
        } catch (e: Throwable) {
            Log.e(TAG, "commitText failed", e)
        }
    }

    /**
     * 添加文件条目到历史
     * @param type 文件类型（IMAGE 或 FILE）
     * @param fileName 显示的文件名
     * @param serverFileName 服务器上的文件名（用于下载）
     * @param fileSize 文件大小
     * @param mimeType MIME 类型
     * @param localFilePath 本地文件路径
     * @param downloadStatus 下载状态
     * @return 是否添加成功（如果已存在相同文件则返回 false）
     */
    fun addFileEntry(
        type: EntryType,
        fileName: String,
        serverFileName: String,
        fileSize: Long? = null,
        mimeType: String? = null,
        localFilePath: String? = null,
        downloadStatus: DownloadStatus = DownloadStatus.NONE
    ): Boolean {
        try {
            val his = getHistory().toMutableList()

            // 检查是否已存在相同的文件（通过 serverFileName 判断）
            val existing = his.firstOrNull {
                it.type == type && it.serverFileName == serverFileName
            }
            if (existing != null) {
                // 如果已存在，更新下载状态和本地路径
                if (downloadStatus != DownloadStatus.NONE || localFilePath != null) {
                    updateFileEntry(existing.id, localFilePath, downloadStatus)
                }
                return false
            }

            val entry = Entry(
                id = UUID.randomUUID().toString(),
                text = "",
                ts = System.currentTimeMillis(),
                pinned = false,
                type = type,
                fileName = fileName,
                fileSize = fileSize,
                mimeType = mimeType,
                localFilePath = localFilePath,
                downloadStatus = downloadStatus,
                serverFileName = serverFileName
            )

            his.add(0, entry)
            while (his.size > MAX_HISTORY) if (his.isNotEmpty()) his.removeAt(his.lastIndex) else break
            sp.edit().putString(KEY_CLIP_HISTORY_JSON, json.encodeToString(his)).apply()
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "addFileEntry failed", t)
            return false
        }
    }

    /**
     * 更新文件条目的下载状态和本地路径
     */
    fun updateFileEntry(
        id: String,
        localFilePath: String?,
        downloadStatus: DownloadStatus
    ): Boolean {
        try {
            val history = getHistory().toMutableList()
            val idx = history.indexOfFirst { it.id == id }
            if (idx >= 0) {
                val old = history[idx]
                history[idx] = old.copy(
                    localFilePath = localFilePath ?: old.localFilePath,
                    downloadStatus = downloadStatus
                )
                sp.edit().putString(KEY_CLIP_HISTORY_JSON, json.encodeToString(history)).apply()
                return true
            }

            // 也检查固定列表
            val pinned = getPinned().toMutableList()
            val idxP = pinned.indexOfFirst { it.id == id }
            if (idxP >= 0) {
                val old = pinned[idxP]
                pinned[idxP] = old.copy(
                    localFilePath = localFilePath ?: old.localFilePath,
                    downloadStatus = downloadStatus
                )
                sp.edit().putString(KEY_CLIP_PINNED_JSON, json.encodeToString(pinned)).apply()
                return true
            }

            return false
        } catch (t: Throwable) {
            Log.e(TAG, "updateFileEntry failed", t)
            return false
        }
    }

    /**
     * 根据 ID 获取条目
     */
    fun getEntryById(id: String): Entry? {
        return getAll().firstOrNull { it.id == id }
    }
}
