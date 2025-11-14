package com.brycewg.asrkb.clipboard

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.InputStream

/**
 * 剪贴板文件管理器
 * 负责管理从服务器下载的文件，存储在 Download/Lexi 目录
 */
class ClipboardFileManager(private val context: Context) {

    companion object {
        private const val TAG = "ClipboardFileManager"
        private const val LEXI_FOLDER = "Lexi"
        private const val MAX_CACHE_SIZE_MB = 500  // 最大缓存 500MB
        private const val MAX_FILE_AGE_DAYS = 30   // 文件最长保留 30 天
    }

    /**
     * 获取 Lexi 文件夹路径 (Download/Lexi)
     */
    private fun getLexiFolder(): File {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val lexiDir = File(downloadDir, LEXI_FOLDER)
        if (!lexiDir.exists()) {
            lexiDir.mkdirs()
        }
        return lexiDir
    }

    /**
     * 获取文件的完整路径
     * @param fileName 文件名
     * @return 文件对象
     */
    fun getFile(fileName: String): File {
        return File(getLexiFolder(), fileName)
    }

    /**
     * 检查文件是否已存在且完整
     * @param fileName 文件名
     * @param expectedSize 预期文件大小（可选，用于验证）
     * @return 是否存在
     */
    fun fileExists(fileName: String, expectedSize: Long? = null): Boolean {
        val file = getFile(fileName)
        if (!file.exists() || !file.isFile) {
            return false
        }

        // 如果提供了预期大小，验证文件大小是否匹配
        if (expectedSize != null && expectedSize > 0) {
            val actualSize = file.length()
            if (actualSize != expectedSize) {
                Log.w(TAG, "File size mismatch: $fileName (expected: $expectedSize, actual: $actualSize)")
                return false
            }
        }

        return true
    }

    /**
     * 保存文件流到本地
     * @param fileName 文件名
     * @param inputStream 输入流
     * @param progressCallback 进度回调（已下载字节数，总字节数）
     * @return 保存后的文件路径，失败返回 null
     */
    fun saveFile(
        fileName: String,
        inputStream: InputStream,
        totalBytes: Long = -1,
        progressCallback: ((Long, Long) -> Unit)? = null
    ): String? {
        return try {
            val file = getFile(fileName)

            // 确保父目录存在
            file.parentFile?.mkdirs()

            // 如果文件已存在，先删除
            if (file.exists()) {
                file.delete()
            }

            var downloadedBytes = 0L
            val buffer = ByteArray(8192)

            file.outputStream().use { output ->
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    progressCallback?.invoke(downloadedBytes, totalBytes)
                }
            }

            Log.d(TAG, "File saved: $fileName -> ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save file: $fileName", e)
            null
        } finally {
            try {
                inputStream.close()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to close input stream", e)
            }
        }
    }

    /**
     * 删除文件
     * @param fileName 文件名
     * @return 是否删除成功
     */
    fun deleteFile(fileName: String): Boolean {
        return try {
            val file = getFile(fileName)
            if (file.exists()) {
                file.delete()
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete file: $fileName", e)
            false
        }
    }

    /**
     * 清理旧文件（超过 MAX_FILE_AGE_DAYS 天的文件）
     * @return 清理的文件数量
     */
    fun cleanOldFiles(): Int {
        val cutoffTime = System.currentTimeMillis() - MAX_FILE_AGE_DAYS * 24 * 60 * 60 * 1000L
        var count = 0

        try {
            val lexiDir = getLexiFolder()
            lexiDir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoffTime) {
                    if (file.delete()) {
                        count++
                        Log.d(TAG, "Deleted old file: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean old files", e)
        }

        return count
    }

    /**
     * 确保缓存大小在限制内，删除最旧的文件
     * @return 清理的文件数量
     */
    fun ensureCacheLimit(): Int {
        var count = 0

        try {
            val lexiDir = getLexiFolder()
            val files = lexiDir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return 0

            var totalSize = files.sumOf { it.length() }
            val maxSize = MAX_CACHE_SIZE_MB * 1024 * 1024L

            for (file in files) {
                if (totalSize <= maxSize) break

                totalSize -= file.length()
                if (file.delete()) {
                    count++
                    Log.d(TAG, "Deleted file to free space: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure cache limit", e)
        }

        return count
    }

    /**
     * 获取缓存文件夹的总大小
     * @return 大小（字节）
     */
    fun getCacheSize(): Long {
        return try {
            val lexiDir = getLexiFolder()
            lexiDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get cache size", e)
            0L
        }
    }

    /**
     * 格式化文件大小为可读字符串
     */
    fun formatFileSize(bytes: Long?): String {
        if (bytes == null || bytes < 0) return "未知大小"

        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }
}
