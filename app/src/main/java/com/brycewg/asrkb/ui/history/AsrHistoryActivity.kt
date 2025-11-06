package com.brycewg.asrkb.ui.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.card.MaterialCardView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.store.AsrHistoryStore
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 识别历史页面
 * - 支持搜索、按供应商/来源筛选
 * - 支持单选/多选删除
 * - 单条一键复制
 * - 分组：2小时内/今天/近一周/近一个月/更早
 */
class AsrHistoryActivity : AppCompatActivity() {
  companion object {
    private const val TAG = "AsrHistoryActivity"
  }

  private enum class TimeFilter { ALL, WITHIN_2H, TODAY, LAST_7D, LAST_30D }

  private lateinit var store: AsrHistoryStore
  private lateinit var adapter: HistoryAdapter
  private lateinit var rv: RecyclerView
  private lateinit var etSearch: TextInputEditText
  private lateinit var tvEmpty: TextView
  private lateinit var chipFilter: com.google.android.material.chip.Chip
  private lateinit var chipSelectAll: com.google.android.material.chip.Chip
  private lateinit var chipClearSelection: com.google.android.material.chip.Chip
  private lateinit var chipDeleteSelected: com.google.android.material.chip.Chip
  private lateinit var chipSelectionCount: com.google.android.material.chip.Chip

  private var allRecords: List<AsrHistoryStore.AsrHistoryRecord> = emptyList()
  private var filtered: List<AsrHistoryStore.AsrHistoryRecord> = emptyList()
  // 全局已选ID集合（与当前筛选结果取交集用于显示与计数）
  private val selectedIdsGlobal: MutableSet<String> = mutableSetOf()
  private var activeVendorIds: Set<String> = emptySet() // 为空表示不过滤
  private var activeSources: Set<String> = emptySet() // "ime"/"floating"；为空表示不过滤
  private var activeTimeFilter: TimeFilter = TimeFilter.ALL
  // 分页：默认一次加载 30 条，向下滚动加载更多；搜索时不分页
  private val pageSize: Int = 30
  private var currentDisplayLimit: Int = pageSize
  private var isLoadingMore: Boolean = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_asr_history)

    // 应用 Window Insets 以适配 Android 15 边缘到边缘显示
    findViewById<android.view.View>(android.R.id.content).let { rootView ->
      com.brycewg.asrkb.ui.WindowInsetsHelper.applySystemBarsInsets(rootView)
    }

    store = AsrHistoryStore(this)

    val tb = findViewById<MaterialToolbar>(R.id.toolbar)
    tb.setTitle(R.string.title_asr_history)
    tb.setNavigationOnClickListener { finish() }
    try {
      setSupportActionBar(tb)
    } catch (e: Exception) {
      Log.w(TAG, "Failed to setSupportActionBar", e)
      // 兜底：手动管理菜单
      tb.inflateMenu(R.menu.menu_asr_history)
      wireToolbarActions(tb.menu)
      tb.setOnMenuItemClickListener { onOptionsItemSelected(it) }
    }

    rv = findViewById(R.id.rvList)
    rv.layoutManager = LinearLayoutManager(this)
    adapter = HistoryAdapter(
      onCopy = { text -> copyToClipboard(text) },
      onSelectionChanged = { updateToolbarTitleWithSelection() }
    )
    rv.adapter = adapter
    // 无限滚动加载更多（仅在未搜索时启用）
    rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
      override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        super.onScrolled(recyclerView, dx, dy)
        if (dy <= 0) return
        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val last = lm.findLastVisibleItemPosition()
        val q = etSearch.text?.toString()?.trim().orEmpty()
        val hasMore = q.isEmpty() && currentDisplayLimit < filtered.size
        if (!isLoadingMore && hasMore && last >= (adapter.itemCount - 4).coerceAtLeast(0)) {
          // 触底前预取下一页
          isLoadingMore = true
          currentDisplayLimit = (currentDisplayLimit + pageSize).coerceAtMost(filtered.size)
          // 仅基于最新 filtered 重渲染（保留选择）
          renderCurrentWithLimit()
          isLoadingMore = false
        }
      }
    })

    chipFilter = findViewById(R.id.chipFilter)
    chipSelectAll = findViewById(R.id.chipSelectAll)
    chipClearSelection = findViewById(R.id.chipClearSelection)
    chipDeleteSelected = findViewById(R.id.chipDeleteSelected)
    chipSelectionCount = findViewById(R.id.chipSelectionCount)
    chipFilter.setOnClickListener { showFilterDialog() }
    chipSelectAll.setOnClickListener { adapter.selectAll(true); updateToolbarTitleWithSelection() }
    chipClearSelection.setOnClickListener { adapter.selectAll(false); updateToolbarTitleWithSelection() }
    chipDeleteSelected.setOnClickListener { confirmDeleteSelected() }

    etSearch = findViewById(R.id.etSearch)
    etSearch.addTextChangedListener(object : TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
      override fun afterTextChanged(s: Editable?) {
        val qNow = s?.toString()?.trim().orEmpty()
        if (qNow.isEmpty()) currentDisplayLimit = pageSize
        applyFilterAndRender()
      }
    })

    tvEmpty = findViewById(R.id.tvEmpty)

    loadData()
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_asr_history, menu)
    wireToolbarActions(menu)
    updateMenuVisibility(menu)
    return true
  }

  override fun onPrepareOptionsMenu(menu: Menu): Boolean {
    updateMenuVisibility(menu)
    // 确保 actionView 存在且有点击行为（某些设备上可能在此时才完成渲染）
    wireToolbarActions(menu)
    return super.onPrepareOptionsMenu(menu)
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    return when (item.itemId) {
      R.id.action_filter -> { showFilterDialog(); true }
      R.id.action_select_all -> { adapter.selectAll(true); updateToolbarTitleWithSelection(); true }
      R.id.action_clear_selection -> { adapter.selectAll(false); updateToolbarTitleWithSelection(); true }
      R.id.action_delete_selected -> { confirmDeleteSelected(); true }
      else -> super.onOptionsItemSelected(item)
    }
  }

  private fun updateMenuVisibility(menu: Menu) {
    val anySelected = adapter.getSelectedCount() > 0
    // 顶栏也不再显示删除按钮
    menu.findItem(R.id.action_delete_selected)?.isVisible = false
    // 顶栏改为单独一行胶囊按钮，隐藏这三项菜单入口
    menu.findItem(R.id.action_clear_selection)?.isVisible = false
    menu.findItem(R.id.action_select_all)?.isVisible = false
    menu.findItem(R.id.action_filter)?.isVisible = false
  }

  private fun updateToolbarTitleWithSelection() {
    try {
      invalidateOptionsMenu()
      val tb = findViewById<MaterialToolbar>(R.id.toolbar)
      val sel = adapter.getSelectedCount()
      tb.subtitle = null
      // 同步更新胶囊按钮显示状态
      val anySelected = sel > 0
      chipClearSelection.visibility = if (anySelected) View.VISIBLE else View.GONE
      chipDeleteSelected.visibility = if (anySelected) View.VISIBLE else View.GONE
      chipSelectAll.visibility = if (!anySelected && adapter.hasData()) View.VISIBLE else View.GONE
      chipFilter.visibility = View.VISIBLE
      chipSelectionCount.text = sel.toString()
      chipSelectionCount.visibility = if (anySelected) View.VISIBLE else View.GONE
    } catch (e: Exception) {
      Log.w(TAG, "Failed to update subtitle", e)
    }
  }
  private fun wireToolbarActions(menu: Menu) {
    menu.findItem(R.id.action_filter)?.actionView?.setOnClickListener { showFilterDialog() }
    menu.findItem(R.id.action_select_all)?.actionView?.setOnClickListener {
      adapter.selectAll(true)
      updateToolbarTitleWithSelection()
    }
    menu.findItem(R.id.action_clear_selection)?.actionView?.setOnClickListener {
      adapter.selectAll(false)
      updateToolbarTitleWithSelection()
    }
  }

  private fun showFilterDialog() {
    val content = layoutInflater.inflate(R.layout.dialog_asr_history_filter, null)
    val cgVendors = content.findViewById<ChipGroup>(R.id.cgVendors)
    val cgSources = content.findViewById<ChipGroup>(R.id.cgSources)
    val cgTime = content.findViewById<ChipGroup>(R.id.cgTime)

    fun createChip(text: String, tag: String): Chip {
      val chip = Chip(this)
      chip.text = text
      chip.tag = tag
      chip.isCheckable = true
      chip.isCheckedIconVisible = false
      chip.isChipIconVisible = false
      chip.id = View.generateViewId()
      return chip
    }

    // Vendors
    val vendors = AsrVendor.values().toList()
    val allVendorChip = createChip(getString(R.string.filter_all), "ALL")
    allVendorChip.isChecked = activeVendorIds.isEmpty()
    cgVendors.addView(allVendorChip)
    vendors.forEach { v ->
      val chip = createChip(getVendorName(v), v.id)
      chip.isChecked = activeVendorIds.isNotEmpty() && activeVendorIds.contains(v.id)
      cgVendors.addView(chip)
    }
    cgVendors.setOnCheckedStateChangeListener { group, checkedIds ->
      val allChip = group.findViewWithTag<Chip>("ALL")
      val hasAll = checkedIds.contains(allChip.id)
      val hasOthers = checkedIds.any { it != allChip.id }
      if (hasAll && hasOthers) {
        // 当选择了其他供应商时，自动取消“不限”
        allChip.isChecked = false
      } else if (hasAll && !hasOthers) {
        // 仅选择“不限”时，确保其他都未选
        for (i in 0 until group.childCount) {
          val c = group.getChildAt(i) as? Chip ?: continue
          if (c.tag != "ALL") c.isChecked = false
        }
      } else {
        // 仅选择了其他供应商：确保“不限”未选中
        allChip.isChecked = false
      }
    }

    // Sources
    val sources = listOf("ime" to getString(R.string.source_ime), "floating" to getString(R.string.source_floating))
    cgSources.isSingleSelection = true
    val allSrcChip = createChip(getString(R.string.filter_all), "ALL")
    val initialSrcTag = if (activeSources.isEmpty()) "ALL" else activeSources.first()
    allSrcChip.isChecked = initialSrcTag == "ALL"
    cgSources.addView(allSrcChip)
    sources.forEach { (id, label) ->
      val chip = createChip(label, id)
      chip.isChecked = initialSrcTag == id
      cgSources.addView(chip)
    }
    cgSources.setOnCheckedStateChangeListener { group, checkedIds ->
      val allChip = group.findViewWithTag<Chip>("ALL")
      // 单选：若选择了“不限”，确保其他不选；若选择了其他，自动取消“不限”
      if (checkedIds.contains(allChip.id)) {
        for (i in 0 until group.childCount) {
          val c = group.getChildAt(i) as? Chip ?: continue
          if (c.tag != "ALL") c.isChecked = false
        }
      } else {
        allChip.isChecked = false
      }
    }

    // Time (single selection)
    cgTime.isSingleSelection = true
    val timeAll = createChip(getString(R.string.filter_all), "all")
    val time2h = createChip(getString(R.string.history_section_2h), "2h")
    val timeToday = createChip(getString(R.string.history_section_today), "today")
    val time7d = createChip(getString(R.string.history_section_7d), "7d")
    val time30d = createChip(getString(R.string.history_section_30d), "30d")
    cgTime.addView(timeAll)
    cgTime.addView(time2h)
    cgTime.addView(timeToday)
    cgTime.addView(time7d)
    cgTime.addView(time30d)
    when (activeTimeFilter) {
      TimeFilter.ALL -> timeAll.isChecked = true
      TimeFilter.WITHIN_2H -> time2h.isChecked = true
      TimeFilter.TODAY -> timeToday.isChecked = true
      TimeFilter.LAST_7D -> time7d.isChecked = true
      TimeFilter.LAST_30D -> time30d.isChecked = true
    }

    AlertDialog.Builder(this)
      .setTitle(R.string.dialog_filter_title)
      .setView(content)
      .setPositiveButton(R.string.dialog_filter_ok) { _, _ ->
        // Read vendors
        val selectedVendorIds = mutableSetOf<String>()
        var vendorAllSelected = false
        for (i in 0 until cgVendors.childCount) {
          val c = cgVendors.getChildAt(i) as? Chip ?: continue
          if (c.isChecked) {
            if (c.tag == "ALL") vendorAllSelected = true else selectedVendorIds.add(c.tag as String)
          }
        }
        activeVendorIds = if (vendorAllSelected || selectedVendorIds.isEmpty()) emptySet() else selectedVendorIds

        // Read sources
        val selectedSources = mutableSetOf<String>()
        var srcAllSelected = false
        for (i in 0 until cgSources.childCount) {
          val c = cgSources.getChildAt(i) as? Chip ?: continue
          if (c.isChecked) {
            if (c.tag == "ALL") srcAllSelected = true else selectedSources.add(c.tag as String)
          }
        }
        activeSources = if (srcAllSelected || selectedSources.isEmpty()) emptySet() else selectedSources

        // Read time
        val checkedTimeId = cgTime.checkedChipId
        val timeChip = cgTime.findViewById<Chip>(checkedTimeId)
        activeTimeFilter = when (timeChip?.tag as? String) {
          "2h" -> TimeFilter.WITHIN_2H
          "today" -> TimeFilter.TODAY
          "7d" -> TimeFilter.LAST_7D
          "30d" -> TimeFilter.LAST_30D
          else -> TimeFilter.ALL
        }
        currentDisplayLimit = pageSize
        applyFilterAndRender()
      }
      .setNeutralButton(R.string.dialog_filter_reset) { _, _ ->
        activeVendorIds = emptySet()
        activeSources = emptySet()
        activeTimeFilter = TimeFilter.ALL
        currentDisplayLimit = pageSize
        applyFilterAndRender()
      }
      .setNegativeButton(R.string.dialog_filter_cancel, null)
      .show()
  }

  private fun confirmDeleteSelected() {
    val ids = adapter.getSelectedIds()
    if (ids.isEmpty()) return
    AlertDialog.Builder(this)
      .setTitle(R.string.dialog_delete_selected_title)
      .setMessage(getString(R.string.dialog_delete_selected_msg, ids.size))
      .setPositiveButton(R.string.dialog_filter_ok) { _, _ ->
        val deleted = try { store.deleteByIds(ids) } catch (e: Exception) { Log.e(TAG, "deleteByIds failed", e); 0 }
        Toast.makeText(this, getString(R.string.toast_deleted, deleted), Toast.LENGTH_SHORT).show()
        adapter.selectAll(false)
        loadData()
      }
      .setNegativeButton(R.string.dialog_filter_cancel, null)
      .show()
  }

  private fun copyToClipboard(text: String) {
    try {
      val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      cm.setPrimaryClip(ClipData.newPlainText("ASR", text))
      Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
      Log.e(TAG, "copyToClipboard failed", e)
    }
  }

  private fun loadData() {
    allRecords = try { store.listAll() } catch (e: Exception) { Log.e(TAG, "listAll failed", e); emptyList() }
    // 重新加载数据时重置分页
    currentDisplayLimit = pageSize
    applyFilterAndRender()
  }

  private fun applyFilterAndRender() {
    val q = etSearch.text?.toString()?.trim().orEmpty()
    val now = System.currentTimeMillis()
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = now
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    val startOfToday = cal.timeInMillis
    val twoHoursMs = 2 * 60 * 60 * 1000L
    val weekMs = 7 * 24 * 60 * 60 * 1000L
    val monthMs = 30 * 24 * 60 * 60 * 1000L
    filtered = allRecords.filter { r ->
      val okVendor = activeVendorIds.isEmpty() || activeVendorIds.contains(r.vendorId)
      val okSrc = activeSources.isEmpty() || activeSources.contains(r.source)
      val okText = q.isEmpty() || r.text.contains(q, ignoreCase = true)
      val okTime = when (activeTimeFilter) {
        TimeFilter.ALL -> true
        TimeFilter.WITHIN_2H -> r.timestamp >= now - twoHoursMs
        TimeFilter.TODAY -> r.timestamp in startOfToday..now
        TimeFilter.LAST_7D -> r.timestamp >= now - weekMs
        TimeFilter.LAST_30D -> r.timestamp >= now - monthMs
      }
      okVendor && okSrc && okText && okTime
    }
    // 变更筛选/搜索后，将全局选择与当前筛选结果取交集，避免跨筛选残留
    run {
      val filteredIdSet = filtered.map { it.id }.toSet()
      selectedIdsGlobal.retainAll(filteredIdSet)
    }
    // 搜索时不分页；未搜索时按分页限制展示
    if (q.isEmpty()) {
      // 若筛选条件变更，重置分页游标（避免显示数量与过滤后总数不协调）
      if (currentDisplayLimit > filtered.size) currentDisplayLimit = filtered.size
      if (currentDisplayLimit <= 0) currentDisplayLimit = pageSize.coerceAtMost(filtered.size)
      renderCurrentWithLimit()
    } else {
      // 搜索：直接展示所有匹配项
      val rows = buildRows(filtered, selectedIdsGlobal)
      adapter.submit(rows)
      tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
      updateToolbarTitleWithSelection()
    }
  }

  private fun renderCurrentWithLimit() {
    val display = if (filtered.isEmpty()) emptyList() else filtered.take(currentDisplayLimit)
    val rows = buildRows(display, selectedIdsGlobal)
    adapter.submit(rows)
    tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    updateToolbarTitleWithSelection()
  }

  private fun getVendorName(v: AsrVendor): String = com.brycewg.asrkb.ui.AsrVendorUi.name(this, v)

  private fun buildRows(list: List<AsrHistoryStore.AsrHistoryRecord>, selected: Set<String> = emptySet()): List<Row> {
    val now = System.currentTimeMillis()
    val startOfToday = java.util.Calendar.getInstance().apply {
      timeInMillis = now
      set(java.util.Calendar.HOUR_OF_DAY, 0)
      set(java.util.Calendar.MINUTE, 0)
      set(java.util.Calendar.SECOND, 0)
      set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val twoHoursMs = 2 * 60 * 60 * 1000L
    val weekMs = 7 * 24 * 60 * 60 * 1000L
    val monthMs = 30 * 24 * 60 * 60 * 1000L

    val rows = mutableListOf<Row>()
    fun addSection(titleRes: Int, items: List<AsrHistoryStore.AsrHistoryRecord>) {
      if (items.isNotEmpty()) {
        rows.add(Row.Header(getString(titleRes)))
        items.forEach { r -> rows.add(Row.Item(r, selected = selected.contains(r.id))) }
      }
    }

    val s2h = list.filter { it.timestamp >= now - twoHoursMs }
    val sToday = list.filter { it.timestamp in startOfToday..(now - twoHoursMs) }
    val s7d = list.filter { it.timestamp in (now - weekMs)..(startOfToday - 1) }
    val s30d = list.filter { it.timestamp in (now - monthMs)..(now - weekMs - 1) }
    val older = list.filter { it.timestamp < now - monthMs }

    addSection(R.string.history_section_2h, s2h)
    addSection(R.string.history_section_today, sToday)
    addSection(R.string.history_section_7d, s7d)
    addSection(R.string.history_section_30d, s30d)
    addSection(R.string.history_section_older, older)
    return rows
  }

  // ================= Adapter =================
  private sealed class Row {
    data class Header(val title: String) : Row()
    data class Item(val rec: AsrHistoryStore.AsrHistoryRecord, var selected: Boolean = false) : Row()
  }

  private class HistoryDiff(
    private val old: List<Row>,
    private val new: List<Row>
  ) : DiffUtil.Callback() {
    override fun getOldListSize() = old.size
    override fun getNewListSize() = new.size
    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
      val a = old[oldItemPosition]
      val b = new[newItemPosition]
      return if (a is Row.Header && b is Row.Header) a.title == b.title
      else if (a is Row.Item && b is Row.Item) a.rec.id == b.rec.id
      else false
    }
    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
      old[oldItemPosition] == new[newItemPosition]
  }

  private inner class HistoryAdapter(
    private val onCopy: (String) -> Unit,
    private val onSelectionChanged: () -> Unit
  ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val TYPE_HEADER = 1
    private val TYPE_ITEM = 2
    private var rows: MutableList<Row> = mutableListOf()

    fun submit(list: List<Row>) {
      val diff = HistoryDiff(rows, list)
      val result = DiffUtil.calculateDiff(diff)
      rows.clear()
      rows.addAll(list)
      result.dispatchUpdatesTo(this)
    }

    fun getSelectedIds(): Set<String> = selectedIdsGlobal.toSet()
    fun getSelectedCount(): Int {
      if (selectedIdsGlobal.isEmpty() || filtered.isEmpty()) return 0
      val filteredIdSet = filtered.map { it.id }.toSet()
      return selectedIdsGlobal.count { filteredIdSet.contains(it) }
    }
    fun hasData(): Boolean = rows.any { it is Row.Item }
    fun selectAll(flag: Boolean) {
      if (flag) {
        selectedIdsGlobal.clear()
        selectedIdsGlobal.addAll(filtered.map { it.id })
      } else {
        selectedIdsGlobal.clear()
      }
      // 同步当前已渲染行的选中态
      rows.forEach { (it as? Row.Item)?.let { row -> row.selected = selectedIdsGlobal.contains(row.rec.id) } }
      notifyDataSetChanged()
      onSelectionChanged()
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
      is Row.Header -> TYPE_HEADER
      is Row.Item -> TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
      return if (viewType == TYPE_HEADER) {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_section_header, parent, false)
        HeaderVH(v)
      } else {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_asr_history, parent, false)
        ItemVH(v)
      }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
      when (val row = rows[position]) {
        is Row.Header -> (holder as HeaderVH).bind(row)
        is Row.Item -> (holder as ItemVH).bind(row)
      }
    }

    override fun getItemCount(): Int = rows.size

    inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
      private val tv = v.findViewById<TextView>(R.id.tvHeader)
      fun bind(row: Row.Header) {
        tv.text = row.title
      }
    }

    inner class ItemVH(v: View) : RecyclerView.ViewHolder(v) {
      private val tvTimestamp = v.findViewById<TextView>(R.id.tvTimestamp)
      private val tvText = v.findViewById<TextView>(R.id.tvText)
      private val tvMeta = v.findViewById<TextView>(R.id.tvMeta)
      private val btnCopy = v.findViewById<MaterialButton>(R.id.btnCopy)

      private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

      fun bind(row: Row.Item) {
        val r = row.rec
        tvTimestamp.text = fmt.format(Date(r.timestamp))
        tvText.text = r.text
        val vendor = mapVendorName(r.vendorId)
        val source = mapSourceFullName(r.source)
        val ai = if (r.aiProcessed) itemView.context.getString(R.string.ai_processed_yes) else itemView.context.getString(R.string.ai_processed_no)
        val charsPart = "${r.charCount}${itemView.context.getString(R.string.unit_chars)}"
        val totalPart = itemView.context.getString(R.string.meta_total_seconds, r.audioMs / 1000.0)
        val parts = mutableListOf(vendor, source, ai, charsPart, totalPart)
        if (r.procMs > 0) {
          parts.add(itemView.context.getString(R.string.meta_proc_seconds, r.procMs / 1000.0))
        }
        tvMeta.text = parts.joinToString("·")

        // 选中高亮（不使用勾选图标），使用更深的 Monet 取色
        val card = itemView as MaterialCardView
        val colorSelected = MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorSecondaryContainer)
        val colorDefault = MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorSurface)
        card.setCardBackgroundColor(if (row.selected) colorSelected else colorDefault)

        itemView.setOnLongClickListener {
          val before = getSelectedCount()
          row.selected = !row.selected
          // 更新全局选择集合
          if (row.selected) selectedIdsGlobal.add(r.id) else selectedIdsGlobal.remove(r.id)
          val after = getSelectedCount()
          if ((before == 0 && after > 0) || (before > 0 && after == 0)) {
            notifyDataSetChanged()
          } else {
            notifyItemChanged(bindingAdapterPosition)
          }
          onSelectionChanged()
          true
        }
        itemView.setOnClickListener {
          val beforeAny = getSelectedCount() > 0
          if (beforeAny) {
            val before = getSelectedCount()
            row.selected = !row.selected
            if (row.selected) selectedIdsGlobal.add(r.id) else selectedIdsGlobal.remove(r.id)
            val after = getSelectedCount()
            if ((before == 0 && after > 0) || (before > 0 && after == 0)) {
              notifyDataSetChanged()
            } else {
              notifyItemChanged(bindingAdapterPosition)
            }
            onSelectionChanged()
          }
        }

        btnCopy.setOnClickListener { onCopy(r.text) }
      }

      private fun mapVendorName(id: String): String = try {
        val v = AsrVendor.fromId(id)
        com.brycewg.asrkb.ui.AsrVendorUi.name(itemView.context, v)
      } catch (e: Exception) {
        id
      }

      private fun mapSourceFullName(src: String): String =
        if (src == "floating") itemView.context.getString(R.string.source_floating_full)
        else itemView.context.getString(R.string.source_ime_full)
    }
  }
}
