package com.brycewg.asrkb.ime

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.brycewg.asrkb.R
import com.brycewg.asrkb.clipboard.ClipboardFileManager
import com.brycewg.asrkb.clipboard.ClipboardHistoryStore
import com.brycewg.asrkb.clipboard.DownloadStatus
import com.brycewg.asrkb.clipboard.EntryType

class ClipboardPanelAdapter(
    private val onItemClick: (ClipboardHistoryStore.Entry) -> Unit,
    private val onFileDownload: (ClipboardHistoryStore.Entry) -> Unit = {}
) : ListAdapter<ClipboardHistoryStore.Entry, ClipboardPanelAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ClipboardHistoryStore.Entry>() {
            override fun areItemsTheSame(oldItem: ClipboardHistoryStore.Entry, newItem: ClipboardHistoryStore.Entry): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ClipboardHistoryStore.Entry, newItem: ClipboardHistoryStore.Entry): Boolean =
                oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_clipboard_entry, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = getItem(position)
        holder.bind(e, onItemClick, onFileDownload)
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tv: TextView = itemView.findViewById(R.id.tvEntry)
        private val pin: View? = itemView.findViewById(R.id.viewPinned)
        private val iconFile: ImageView = itemView.findViewById(R.id.iconFile)
        private val btnDownload: Button = itemView.findViewById(R.id.btnDownload)
        private val fileManager = ClipboardFileManager(itemView.context)

        fun bind(
            e: ClipboardHistoryStore.Entry,
            onClick: (ClipboardHistoryStore.Entry) -> Unit,
            onDownload: (ClipboardHistoryStore.Entry) -> Unit
        ) {
            when (e.type) {
                EntryType.TEXT -> {
                    // 文本类型：显示文本内容
                    tv.text = e.text
                    iconFile.visibility = View.GONE
                    btnDownload.visibility = View.GONE
                    itemView.setOnClickListener { onClick(e) }
                }
                EntryType.IMAGE, EntryType.FILE -> {
                    // 文件类型：显示文件名和大小
                    val sizeText = fileManager.formatFileSize(e.fileSize)
                    tv.text = "${e.fileName ?: "未知文件"} ($sizeText)"

                    // 显示文件图标
                    iconFile.visibility = View.VISIBLE
                    val iconRes = when (e.type) {
                        EntryType.IMAGE -> android.R.drawable.ic_menu_gallery
                        EntryType.FILE -> android.R.drawable.ic_menu_info_details
                        else -> android.R.drawable.ic_menu_info_details
                    }
                    iconFile.setImageResource(iconRes)

                    // 根据下载状态显示不同的按钮
                    when (e.downloadStatus) {
                        DownloadStatus.NONE -> {
                            btnDownload.visibility = View.VISIBLE
                            btnDownload.text = "下载"
                            btnDownload.isEnabled = true
                            btnDownload.setOnClickListener { onDownload(e) }
                            itemView.setOnClickListener(null)
                        }
                        DownloadStatus.COMPLETED -> {
                            btnDownload.visibility = View.GONE
                            // 已下载的文件可以点击打开
                            itemView.setOnClickListener { onClick(e) }
                        }
                        DownloadStatus.DOWNLOADING -> {
                            btnDownload.visibility = View.VISIBLE
                            btnDownload.text = "下载中..."
                            btnDownload.isEnabled = false
                            itemView.setOnClickListener(null)
                        }
                        DownloadStatus.FAILED -> {
                            btnDownload.visibility = View.VISIBLE
                            btnDownload.text = "重试"
                            btnDownload.isEnabled = true
                            btnDownload.setOnClickListener { onDownload(e) }
                            itemView.setOnClickListener(null)
                        }
                    }
                }
            }

            pin?.visibility = if (e.pinned) View.VISIBLE else View.GONE
        }
    }
}

