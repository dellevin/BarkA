package com.trah.accnotify.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
import com.trah.accnotify.AccnotifyApp
import com.trah.accnotify.databinding.FragmentMessagesBinding
import com.trah.accnotify.data.Message
import com.trah.accnotify.R
import com.trah.accnotify.util.NotificationHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.linkify.LinkifyPlugin
import io.noties.markwon.movement.MovementMethodPlugin
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MessagesFragment : Fragment() {

    private var _binding: FragmentMessagesBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: MessageAdapter
    
    // 待显示的消息ID（从通知点击进入时使用）
    private var pendingMessageId: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        selectedGroup = savedInstanceState?.getString("selected_message_group") ?: selectedGroup
        savedInstanceState?.let { state ->
            timeFilter = TimeFilter.values()[state.getInt("time_filter_option", 0)]
            customStart = state.getLong("time_filter_start", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
            customEnd = state.getLong("time_filter_end", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
        }
        _binding = FragmentMessagesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = MessageAdapter(
            requireContext(),
            onItemClick = { message -> showMessageDetailDialog(message) },
            onDelete = { message -> deleteMessage(message) }
        )
        setupUI()
        observeMessages()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("selected_message_group", selectedGroup)
        outState.putInt("time_filter_option", timeFilter.ordinal)
        customStart?.let { outState.putLong("time_filter_start", it) }
        customEnd?.let { outState.putLong("time_filter_end", it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private var allMessages: List<Message> = emptyList()
    private var selectedGroup: String? = null

    private enum class TimeFilter(val label: String) {
        ALL("时间筛选"), TODAY("今天"), DAYS_7("最近 7 天"), DAYS_30("最近 30 天"), CUSTOM("自定义时间")
    }
    private var timeFilter = TimeFilter.ALL
    private var customStart: Long? = null
    private var customEnd: Long? = null
    private val rangeDayFormat = SimpleDateFormat("MM-dd", Locale.getDefault())

    fun showGroupMessages(group: String?) {
        selectedGroup = group?.trim()
        if (_binding != null && ::adapter.isInitialized) {
            updateGroupFilterLabel()
            filterMessages(binding.etSearch.text.toString())
        }
    }

    private fun updateGroupFilterLabel() {
        binding.btnGroupFilter.text = selectedGroup?.ifEmpty { "未分组" } ?: "全部分组"
        binding.btnGroupFilter.contentDescription = "选择消息分组，当前${binding.btnGroupFilter.text}"
    }

    private fun showGroupFilter() {
        val groups = allMessages.map { it.group?.trim().orEmpty() }.distinct().sorted()
        val options = listOf<String?>(null) + groups
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val surface = androidx.core.content.ContextCompat.getColor(ctx, R.color.bark_component_bg)
        val textColor = androidx.core.content.ContextCompat.getColor(ctx, R.color.bark_component_text)
        val accent = textColor
        val outline = androidx.core.content.ContextCompat.getColor(ctx, R.color.bark_component_control_border)
        val popup = androidx.appcompat.widget.ListPopupWindow(ctx).apply {
            anchorView = binding.btnGroupFilter
            isModal = true
            width = minOf((240 * density).toInt(), resources.displayMetrics.widthPixels - (32 * density).toInt())
            setDropDownGravity(android.view.Gravity.END)
            setInputMethodMode(android.widget.PopupWindow.INPUT_METHOD_NOT_NEEDED)
            setBackgroundDrawable(com.google.android.material.shape.MaterialShapeDrawable(
                com.google.android.material.shape.ShapeAppearanceModel.builder()
                    .setAllCornerSizes(12 * density).build()).apply {
                fillColor = android.content.res.ColorStateList.valueOf(surface)
                setStroke(density, outline)
            })
        }
        popup.setAdapter(object : android.widget.BaseAdapter() {
            override fun getCount() = options.size
            override fun getItem(position: Int) = options[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView as? com.google.android.material.radiobutton.MaterialRadioButton
                    ?: com.google.android.material.radiobutton.MaterialRadioButton(ctx).apply {
                        minHeight = resources.getDimensionPixelSize(R.dimen.action_touch_size)
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(com.trah.accnotify.R.dimen.text_body))
                        setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                        isClickable = false
                        isFocusable = false
                        setUseMaterialThemeColors(true)
                    }
                val group = options[position]
                row.text = group?.ifEmpty { "未分组" } ?: "全部分组"
                row.isChecked = selectedGroup == group
                row.setTextColor(if (row.isChecked) accent else textColor)
                return row
            }
        })
        popup.setOnItemClickListener { _, _, position, _ ->
            showGroupMessages(options[position])
            popup.dismiss()
        }
        popup.show()
        popup.setSelection(options.indexOf(selectedGroup))
    }
    
    /**
     * 通过消息ID显示消息详情（从通知点击调用）
     */
    fun showMessageById(messageId: String) {
        // 如果消息列表已加载，直接查找并显示
        val message = allMessages.find { it.messageId == messageId }
        if (message != null) {
            showMessageDetailDialog(message)
        } else {
            // 消息列表还未加载，保存ID等待加载后显示
            pendingMessageId = messageId
        }
    }

    private fun setupUI() {
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter
        setupSwipeDelete()
        updateGroupFilterLabel()
        updateTimeFilterLabel()
        binding.btnGroupFilter.setOnClickListener { showGroupFilter() }

        binding.fabClear.setOnClickListener {
            showClearOptions()
        }
        binding.btnTimeFilter.setOnClickListener { showTimeFilter() }

        // Search functionality
        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                filterMessages(s?.toString() ?: "")
            }
        })
    }

    private fun deleteMessage(message: Message) {
        adapter.closeReveal()
        adapter.removeMessage(message.messageId)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                AccnotifyApp.getInstance().database.messageDao().delete(message.messageId)
                context?.let { NotificationHelper.cancelNotification(it, message.messageId) }
                context?.let { Toast.makeText(it, "消息已删除", Toast.LENGTH_SHORT).show() }
            } catch (_: Exception) {
                if (_binding != null) {
                    filterMessages(binding.etSearch.text.toString())
                    Toast.makeText(context, "删除失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupSwipeDelete() {
        val revealWidth = (76 * resources.displayMetrics.density)
        // True while a drag is active; scroll events during a horizontal swipe must
        // not close the reveal (tiny vertical finger jitter otherwise retracts it).
        var swiping = false
        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(recyclerView: RecyclerView, source: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder) = false

            // Never auto-complete a swipe: the card must stay on screen so the
            // revealed delete button can be tapped explicitly.
            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = 2f
            override fun getSwipeEscapeVelocity(defaultValue: Float) = Float.MAX_VALUE

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                swiping = actionState == ItemTouchHelper.ACTION_STATE_SWIPE
                if (swiping && viewHolder is MessageAdapter.ViewHolder) {
                    adapter.openHolder?.takeIf { it != viewHolder }?.let { adapter.closeReveal() }
                }
            }

            override fun onChildDraw(canvas: android.graphics.Canvas, recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder, dX: Float, dY: Float,
                actionState: Int, isCurrentlyActive: Boolean) {
                if (actionState != ItemTouchHelper.ACTION_STATE_SWIPE) return
                val holder = viewHolder as? MessageAdapter.ViewHolder ?: return
                // Follow the finger only while dragging. After release ItemTouchHelper runs
                // its own recover animation whose dX drifts back to 0; ignore those frames
                // so the card stays put and clearView() can snap it open or closed.
                if (isCurrentlyActive) {
                    holder.contentView.translationX = dX.coerceIn(-revealWidth, 0f)
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                val holder = viewHolder as? MessageAdapter.ViewHolder ?: return
                // ItemTouchHelper has already crossed touch slop before a swipe starts.
                // Keep any released left swipe open; dragging fully back to zero still closes it.
                val reveal = holder.contentView.translationX < 0f
                val target = if (reveal) -revealWidth else 0f
                holder.contentView.animate().translationX(target).setDuration(180).start()
                adapter.markOpen(if (reveal) holder else null)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.recyclerView)
        // Scrolling away hides a revealed button, but never while a swipe is in progress.
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!swiping && (dx != 0 || dy != 0)) adapter.closeReveal()
            }
        })
    }

    private fun currentTimeRange(): Pair<Long?, Long?> {
        val now = System.currentTimeMillis()
        return when (timeFilter) {
            TimeFilter.ALL -> null to null
            TimeFilter.TODAY -> {
                Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis to null
            }
            TimeFilter.DAYS_7 -> now - 7L * 24 * 60 * 60 * 1000 to null
            TimeFilter.DAYS_30 -> now - 30L * 24 * 60 * 60 * 1000 to null
            TimeFilter.CUSTOM -> customStart to customEnd
        }
    }

    private fun updateTimeFilterLabel() {
        binding.btnTimeFilter.text = if (timeFilter == TimeFilter.CUSTOM && customStart != null) {
            val end = customEnd
            if (end != null) {
                "${rangeDayFormat.format(java.util.Date(customStart!!))}~${rangeDayFormat.format(java.util.Date(end - 1))}"
            } else {
                rangeDayFormat.format(java.util.Date(customStart!!)) + " 起"
            }
        } else {
            timeFilter.label
        }
    }

    private fun showTimeFilter() {
        val options = TimeFilter.values().map { it.label }
        showRadioPopup(
            anchor = binding.btnTimeFilter,
            options = options,
            selectedIndex = timeFilter.ordinal
        ) { index ->
            val chosen = TimeFilter.values()[index]
            if (chosen == TimeFilter.CUSTOM) {
                pickCustomRange()
            } else {
                timeFilter = chosen
                updateTimeFilterLabel()
                filterMessages(binding.etSearch.text.toString())
            }
        }
    }

    private fun pickCustomRange() {
        val calendar = Calendar.getInstance()
        android.app.DatePickerDialog(
            requireContext(),
            R.style.Theme_Accnotify_AppDialog,
            { _, startYear, startMonth, startDay ->
                val start = Calendar.getInstance().apply {
                    set(startYear, startMonth, startDay, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                android.app.DatePickerDialog(
                    requireContext(),
                    R.style.Theme_Accnotify_AppDialog,
                    { _, endYear, endMonth, endDay ->
                        val endExclusive = Calendar.getInstance().apply {
                            set(endYear, endMonth, endDay, 0, 0, 0)
                            set(Calendar.MILLISECOND, 0)
                            add(Calendar.DAY_OF_MONTH, 1)
                        }.timeInMillis
                        if (endExclusive <= start) {
                            Toast.makeText(requireContext(), "结束日期不能早于开始日期", Toast.LENGTH_SHORT).show()
                            return@DatePickerDialog
                        }
                        timeFilter = TimeFilter.CUSTOM
                        customStart = start
                        customEnd = endExclusive
                        updateTimeFilterLabel()
                        filterMessages(binding.etSearch.text.toString())
                    },
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH),
                    calendar.get(Calendar.DAY_OF_MONTH)
                ).apply { setTitle("选择结束日期") }.show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).apply { setTitle("选择开始日期") }.show()
    }

    private fun showClearOptions() {
        val options = listOf("清空全部消息", "删除 7 天前", "删除 30 天前", "自定义时间…")
        showRadioPopup(anchor = binding.fabClear, options = options, selectedIndex = -1) { index ->
            when (index) {
                0 -> showClearConfirmDialog()
                1 -> confirmDeleteOlderThan(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000, "7 天前")
                2 -> confirmDeleteOlderThan(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000, "30 天前")
                3 -> pickClearCutoff()
            }
        }
    }

    private fun pickClearCutoff() {
        val calendar = Calendar.getInstance()
        android.app.DatePickerDialog(
            requireContext(),
            R.style.Theme_Accnotify_AppDialog,
            { _, year, month, day ->
                val cutoff = Calendar.getInstance().apply {
                    set(year, month, day, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val dateText = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(java.util.Date(cutoff))
                confirmDeleteOlderThan(cutoff, dateText)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).apply { setTitle("删除该日期之前的消息") }.show()
    }

    private fun confirmDeleteOlderThan(cutoff: Long, cutoffLabel: String) {
        showCleanDialog(
            title = "清空消息",
            message = "确定删除 $cutoffLabel 之前的所有消息吗？此操作不可恢复。",
            positiveText = "删除",
            onPositive = {
                viewLifecycleOwner.lifecycleScope.launch {
                    AccnotifyApp.getInstance().database.messageDao().deleteOlderThan(cutoff)
                }
            }
        )
    }

    /**
     * Shared single-choice popup used by the filter chips and the clear FAB.
     */
    private fun showRadioPopup(anchor: View, options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val surface = androidx.core.content.ContextCompat.getColor(ctx, R.color.bark_component_bg)
        val textColor = androidx.core.content.ContextCompat.getColor(ctx, R.color.bark_component_text)
        val accent = textColor
        val outline = androidx.core.content.ContextCompat.getColor(ctx, R.color.bark_component_control_border)
        val popup = androidx.appcompat.widget.ListPopupWindow(ctx).apply {
            anchorView = anchor
            isModal = true
            width = minOf((240 * density).toInt(), resources.displayMetrics.widthPixels - (32 * density).toInt())
            setDropDownGravity(android.view.Gravity.END)
            setInputMethodMode(android.widget.PopupWindow.INPUT_METHOD_NOT_NEEDED)
            setBackgroundDrawable(com.google.android.material.shape.MaterialShapeDrawable(
                com.google.android.material.shape.ShapeAppearanceModel.builder()
                    .setAllCornerSizes(12 * density).build()).apply {
                fillColor = android.content.res.ColorStateList.valueOf(surface)
                setStroke(density, outline)
            })
        }
        popup.setAdapter(object : android.widget.BaseAdapter() {
            override fun getCount() = options.size
            override fun getItem(position: Int) = options[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView as? com.google.android.material.radiobutton.MaterialRadioButton
                    ?: com.google.android.material.radiobutton.MaterialRadioButton(ctx).apply {
                        minHeight = resources.getDimensionPixelSize(R.dimen.action_touch_size)
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_body))
                        setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                        isClickable = false
                        isFocusable = false
                        setUseMaterialThemeColors(true)
                    }
                row.text = options[position]
                row.isChecked = position == selectedIndex
                row.setTextColor(if (row.isChecked) accent else textColor)
                return row
            }
        })
        popup.setOnItemClickListener { _, _, position, _ ->
            onSelect(position)
            popup.dismiss()
        }
        popup.show()
        if (selectedIndex >= 0) popup.setSelection(selectedIndex)
    }

    private fun filterMessages(query: String) {
        val (rangeStart, rangeEnd) = currentTimeRange()
        val filtered = allMessages.filter { msg ->
            (selectedGroup == null || msg.group?.trim().orEmpty() == selectedGroup) &&
                (rangeStart == null || msg.timestamp.time >= rangeStart) &&
                (rangeEnd == null || msg.timestamp.time < rangeEnd) &&
                (query.isBlank() || msg.title?.contains(query, ignoreCase = true) == true ||
                    msg.body?.contains(query, ignoreCase = true) == true)
        }
        adapter.submitList(filtered)
        binding.tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun observeMessages() {
        // Show loading
        binding.progressLoading.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE
        
        viewLifecycleOwner.lifecycleScope.launch {
            AccnotifyApp.getInstance().database.messageDao()
                .getAllMessages()
                .collectLatest { messages ->
                    // Hide loading
                    binding.progressLoading.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                    
                    allMessages = messages
                    filterMessages(binding.etSearch.text?.toString() ?: "")
                    
                    // 检查是否有待显示的消息
                    pendingMessageId?.let { msgId ->
                        val message = messages.find { it.messageId == msgId }
                        if (message != null) {
                            pendingMessageId = null
                            showMessageDetailDialog(message)
                        }
                    }
                }
        }
    }

    /**
     * 显示消息详情对话框
     */
    private fun showMessageDetailDialog(message: Message) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_message_detail, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.dialogTitle)
        val tvTime = dialogView.findViewById<TextView>(R.id.dialogTime)
        val tvBody = dialogView.findViewById<TextView>(R.id.dialogBody)
        val scrollBody = dialogView.findViewById<androidx.core.widget.NestedScrollView>(R.id.scrollBody)
        val btnCopy = dialogView.findViewById<TextView>(R.id.btnCopy)
        val btnClose = dialogView.findViewById<TextView>(R.id.btnClose)
        val btnOpenUrl = dialogView.findViewById<TextView>(R.id.btnOpenUrl)
        val messageUrl = message.url?.trim().orEmpty()
        if (messageUrl.isNotEmpty()) {
            btnOpenUrl.visibility = View.VISIBLE
            btnOpenUrl.setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(messageUrl))
                        .addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: android.content.ActivityNotFoundException) {
                    Toast.makeText(requireContext(), "没有可以打开此链接的应用", Toast.LENGTH_LONG).show()
                } catch (_: SecurityException) {
                    Toast.makeText(requireContext(), "系统不允许打开此链接", Toast.LENGTH_LONG).show()
                }
            }
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        
        tvTitle.text = message.title ?: "BarkA"
        tvTime.text = dateFormat.format(message.timestamp)
        
        // 格式化消息内容（使用 Markwon 渲染 Markdown，启用 SoftBreak 保留换行，启用链接点击）
        val markwon = Markwon.builder(requireContext())
            .usePlugin(SoftBreakAddsNewLinePlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(MovementMethodPlugin.create())
            .build()
        
        val body = message.body ?: ""
        val trimmedBody = body.trim()
        val isJson = (trimmedBody.startsWith("{") && trimmedBody.endsWith("}")) ||
                     (trimmedBody.startsWith("[") && trimmedBody.endsWith("]"))
        val hasSeparator = trimmedBody.contains("--- 完整数据 ---")
        
        // Show formatted content only (without raw section) by default
        val formattedOnly = if (isJson || hasSeparator) {
            formatMessageBodyForDisplay(body)
        } else {
            body
        }
        markwon.setMarkdown(tvBody, formattedOnly)
        if (messageUrl.isNotEmpty() && !formattedOnly.contains(messageUrl)) {
            val content = android.text.SpannableStringBuilder(tvBody.text)
            if (content.isNotEmpty()) content.append("\n\n")
            val start = content.length
            content.append(messageUrl)
            content.setSpan(object : android.text.style.ClickableSpan() {
                override fun onClick(widget: View) { btnOpenUrl.performClick() }
                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.color = resources.getColor(R.color.message_detail_text, null)
                    ds.isUnderlineText = true
                }
            }, start, content.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            tvBody.text = content
        }
        
        // Add "View Raw Content" toggle if content is JSON or has separator
        if (isJson || hasSeparator) {
            val frameLayout = scrollBody.parent as FrameLayout
            val parentLayout = frameLayout.parent as android.widget.LinearLayout
            val index = parentLayout.indexOfChild(frameLayout)
            
            // Raw content container (hidden by default)
            val rawScrollView = androidx.core.widget.NestedScrollView(requireContext()).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = resources.getDimensionPixelSize(R.dimen.spacing_small)
                }
                setPadding(
                    0,
                    resources.getDimensionPixelSize(R.dimen.spacing_control),
                    0,
                    resources.getDimensionPixelSize(R.dimen.spacing_control)
                )
                setBackgroundResource(R.color.message_detail_background)
                visibility = View.GONE
            }
            val rawText = TextView(requireContext()).apply {
                text = trimmedBody
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(com.trah.accnotify.R.dimen.text_body))
                setTextColor(resources.getColor(R.color.message_detail_secondary, null))
                setTypeface(android.graphics.Typeface.MONOSPACE)
                setTextIsSelectable(true)
            }
            rawScrollView.addView(rawText)
            
            // Toggle button
            val toggleBtn = TextView(requireContext()).apply {
                text = "点击查看原始内容 ▼"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(com.trah.accnotify.R.dimen.text_body))
                setTextColor(resources.getColor(R.color.message_detail_text, null))
                setPadding(0, resources.getDimensionPixelSize(R.dimen.spacing_control), 0, (4 * resources.displayMetrics.density).toInt())
                setOnClickListener {
                    if (rawScrollView.visibility == View.GONE) {
                        rawScrollView.visibility = View.VISIBLE
                        this.text = "收起原始内容 ▲"
                    } else {
                        rawScrollView.visibility = View.GONE
                        this.text = "点击查看原始内容 ▼"
                    }
                }
            }
            
            parentLayout.addView(toggleBtn, index + 1)
            parentLayout.addView(rawScrollView, index + 2)
        }
        
        // 设置滚动区域最大高度为屏幕高度的 50%
        val displayMetrics = resources.displayMetrics
        val maxHeight = (displayMetrics.heightPixels * 0.5).toInt()
        scrollBody.post {
            if (scrollBody.height > maxHeight) {
                val params = scrollBody.layoutParams
                params.height = maxHeight
                scrollBody.layoutParams = params
            }
        }

        // Display image if present (supports both base64 and URL)
        if (!message.image.isNullOrEmpty()) {
            val imageContent = message.image
            val isUrl = imageContent.startsWith("http://") || imageContent.startsWith("https://")
            
            val frameLayout = scrollBody.parent as FrameLayout
            val parentLayout = frameLayout.parent as android.widget.LinearLayout
            val insertIndex = parentLayout.indexOfChild(frameLayout) + 1
            // Account for raw content toggle views that may have been added above
            val actualInsertIndex = if (isJson || hasSeparator) insertIndex + 2 else insertIndex
            
            if (isUrl) {
                // Loading indicator
                val loadingText = TextView(requireContext()).apply {
                    text = "图片加载中..."
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(com.trah.accnotify.R.dimen.text_body))
                    setTextColor(resources.getColor(R.color.message_detail_secondary, null))
                    setPadding(0, resources.getDimensionPixelSize(R.dimen.spacing_control), 0, (4 * resources.displayMetrics.density).toInt())
                }
                parentLayout.addView(loadingText, actualInsertIndex)
                
                // ImageView with minimum height so it's visible
                val imageView = android.widget.ImageView(requireContext()).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = resources.getDimensionPixelSize(R.dimen.spacing_small)
                    }
                    adjustViewBounds = true
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    visibility = View.GONE
                }
                parentLayout.addView(imageView, actualInsertIndex + 1)
                
                // "Show original URL" button
                val urlBtn = TextView(requireContext()).apply {
                    text = "查看原始图片链接"
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(com.trah.accnotify.R.dimen.text_body))
                    setTextColor(resources.getColor(R.color.message_detail_text, null))
                    setPadding(0, resources.getDimensionPixelSize(R.dimen.spacing_small), 0, (4 * resources.displayMetrics.density).toInt())
                    setOnClickListener {
                        showCleanDialog(
                            title = "图片链接",
                            message = imageContent,
                            positiveText = "复制链接",
                            onPositive = {
                                copyToClipboard(imageContent)
                                Toast.makeText(requireContext(), "已复制图片链接", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
                parentLayout.addView(urlBtn, actualInsertIndex + 2)
                
                // Download image in background
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val url = java.net.URL(imageContent)
                            val connection = url.openConnection() as java.net.HttpURLConnection
                            connection.connectTimeout = 15000
                            connection.readTimeout = 15000
                            connection.instanceFollowRedirects = true
                            connection.setRequestProperty("User-Agent", "Accnotify/1.0")
                            connection.setRequestProperty("Accept", "image/*,*/*")
                            connection.doInput = true
                            connection.connect()
                            
                            val responseCode = connection.responseCode
                            if (responseCode != java.net.HttpURLConnection.HTTP_OK) {
                                throw Exception("HTTP $responseCode")
                            }
                            
                            val inputStream = connection.inputStream
                            val result = android.graphics.BitmapFactory.decodeStream(inputStream)
                            inputStream.close()
                            connection.disconnect()
                            result
                        }
                        if (bitmap != null && isAdded) {
                            loadingText.visibility = View.GONE
                            imageView.visibility = View.VISIBLE
                            imageView.setImageBitmap(bitmap)
                            imageView.setOnLongClickListener {
                                showImageOptionsDialog(bitmap)
                                true
                            }
                        } else if (isAdded) {
                            loadingText.text = "图片加载失败（无法解码）"
                            loadingText.setTextColor(resources.getColor(R.color.message_detail_secondary, null))
                        }
                    } catch (e: Exception) {
                        if (isAdded) {
                            loadingText.text = "图片加载失败: ${e.message}"
                            loadingText.setTextColor(resources.getColor(R.color.message_detail_secondary, null))
                            android.util.Log.e("MessagesFragment", "Failed to load image from URL: $imageContent", e)
                        }
                    }
                }
            } else {
                // Base64 image
                try {
                    val base64Data = if (imageContent.contains(",")) {
                        imageContent.substringAfter(",")
                    } else {
                        imageContent
                    }
                    val imageBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    if (bitmap != null) {
                        val imageView = android.widget.ImageView(requireContext()).apply {
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                topMargin = resources.getDimensionPixelSize(R.dimen.spacing_control)
                            }
                            adjustViewBounds = true
                            setImageBitmap(bitmap)
                            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        }
                        imageView.setOnLongClickListener {
                            showImageOptionsDialog(bitmap)
                            true
                        }
                        parentLayout.addView(imageView, actualInsertIndex)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MessagesFragment", "Failed to decode image", e)
                }
            }
        }

        val alertDialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        alertDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCopy.setOnClickListener {
            val bodyToCopy = message.body.orEmpty()
            copyToClipboard(if (messageUrl.isNotEmpty() && !bodyToCopy.contains(messageUrl))
                listOf(bodyToCopy, messageUrl).filter { it.isNotEmpty() }.joinToString("\n\n") else bodyToCopy)
            Toast.makeText(requireContext(), "已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }

        btnClose.setOnClickListener {
            alertDialog.dismiss()
        }

        alertDialog.show()
        KeyDialogControls.compactWindow(requireContext(), alertDialog.window)
    }

    /**
     * 将消息正文转换为 Markdown 格式
     */
    private fun formatMessageBodyAsMarkdown(body: String): String {
        val trimmed = body.trim()

        // 处理包含 "--- 完整数据 ---" 分隔符的消息
        val separatorIndex = trimmed.indexOf("--- 完整数据 ---")
        if (separatorIndex > 0) {
            val summary = trimmed.substring(0, separatorIndex).trim()
            val fullData = trimmed.substring(separatorIndex + "--- 完整数据 ---".length).trim()
            val summaryMd = jsonToReadableMarkdown(summary)
            return summaryMd + "\n\n---\n\n**原始内容:**\n```\n$fullData\n```"
        }

        // JSON 对象或数组 - show formatted content with raw toggle
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            val formatted = jsonToReadableMarkdown(trimmed)
            // Only show raw section if the formatted version differs from input
            if (formatted != trimmed) {
                return formatted + "\n\n---\n\n**原始内容:**\n```\n$trimmed\n```"
            }
            return formatted
        }

        return body
    }

    /**
     * 格式化消息正文用于详情对话框显示（仅格式化内容，不含原始数据部分）
     */
    private fun formatMessageBodyForDisplay(body: String): String {
        val trimmed = body.trim()

        val separatorIndex = trimmed.indexOf("--- 完整数据 ---")
        if (separatorIndex > 0) {
            val summary = trimmed.substring(0, separatorIndex).trim()
            return jsonToReadableMarkdown(summary)
        }

        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return jsonToReadableMarkdown(trimmed)
        }

        return body
    }

    /**
     * 将 JSON 字符串转换为可读的 Markdown 文本
     */
    private fun jsonToReadableMarkdown(text: String, indent: Int = 0): String {
        val trimmed = text.trim()
        if (!((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
              (trimmed.startsWith("[") && trimmed.endsWith("]")))) {
            return text
        }
        return try {
            val element = com.google.gson.JsonParser.parseString(trimmed)
            val sb = StringBuilder()
            renderJsonElement(element, sb, indent)
            sb.toString().trimEnd()
        } catch (e: Exception) {
            text
        }
    }

    private fun renderJsonElement(element: com.google.gson.JsonElement, sb: StringBuilder, indent: Int) {
        val prefix = "  ".repeat(indent)
        when {
            element.isJsonObject -> {
                val obj = element.asJsonObject
                for ((key, value) in obj.entrySet()) {
                    when {
                        value.isJsonPrimitive || value.isJsonNull -> {
                            sb.appendLine("${prefix}**$key**: ${value.asJsonPrimitiveText()}")
                        }
                        value.isJsonObject -> {
                            sb.appendLine("${prefix}**$key**:")
                            renderJsonElement(value, sb, indent + 1)
                        }
                        value.isJsonArray -> {
                            sb.appendLine("${prefix}**$key**:")
                            renderJsonElement(value, sb, indent + 1)
                        }
                    }
                }
            }
            element.isJsonArray -> {
                val arr = element.asJsonArray
                arr.forEachIndexed { index, item ->
                    when {
                        item.isJsonPrimitive || item.isJsonNull -> {
                            sb.appendLine("${prefix}${index + 1}. ${item.asJsonPrimitiveText()}")
                        }
                        else -> {
                            sb.appendLine("${prefix}${index + 1}.")
                            renderJsonElement(item, sb, indent + 1)
                        }
                    }
                }
            }
            else -> sb.appendLine("$prefix${element.asJsonPrimitiveText()}")
        }
    }

    private fun com.google.gson.JsonElement.asJsonPrimitiveText(): String {
        return when {
            isJsonNull -> "null"
            isJsonPrimitive -> {
                val p = asJsonPrimitive
                if (p.isString) p.asString else p.toString()
            }
            else -> toString()
        }
    }

    /**
     * 显示图片操作选项（保存/分享）
     */
    private fun showImageOptionsDialog(bitmap: android.graphics.Bitmap) {
        val items = arrayOf("保存到相册", "分享图片")
        AlertDialog.Builder(requireContext(), R.style.Theme_Accnotify_AppDialog)
            .setTitle("图片操作")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> saveImageToGallery(bitmap)
                    1 -> shareImage(bitmap)
                }
            }
            .show()
    }

    private fun saveImageToGallery(bitmap: android.graphics.Bitmap) {
        try {
            val filename = "accnotify_${System.currentTimeMillis()}.png"
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Accnotify")
                }
            }
            val uri = requireContext().contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
            )
            uri?.let {
                requireContext().contentResolver.openOutputStream(it)?.use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }
                Toast.makeText(requireContext(), "已保存到相册", Toast.LENGTH_SHORT).show()
            } ?: Toast.makeText(requireContext(), "保存失败", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareImage(bitmap: android.graphics.Bitmap) {
        try {
            val cachePath = java.io.File(requireContext().cacheDir, "shared_images")
            cachePath.mkdirs()
            val file = java.io.File(cachePath, "accnotify_${System.currentTimeMillis()}.png")
            java.io.FileOutputStream(file).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "分享图片"))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 复制内容到剪贴板
     */
    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("message", text)
        clipboard.setPrimaryClip(clip)
    }

    private fun showClearConfirmDialog() {
        showCleanDialog(
            title = "清空消息",
            message = "确定清空所有消息记录吗？此操作不可恢复。",
            positiveText = "清空",
            onPositive = {
                viewLifecycleOwner.lifecycleScope.launch {
                    AccnotifyApp.getInstance().database.messageDao().deleteAll()
                    context?.let { NotificationHelper.cancelAllMessageNotifications(it) }
                }
            }
        )
    }

    private fun showCleanDialog(
        title: String,
        message: String,
        positiveText: String = "确定",
        negativeText: String? = "取消",
        customView: View? = null,
        onPositive: (() -> Unit)? = null
    ) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_clean, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.dialogTitle)
        val tvMessage = dialogView.findViewById<TextView>(R.id.dialogMessage)
        val btnPositive = dialogView.findViewById<TextView>(R.id.btnPositive)
        val btnNegative = dialogView.findViewById<TextView>(R.id.btnNegative)
        val customContainer = dialogView.findViewById<FrameLayout>(R.id.dialogCustomContainer)

        tvTitle.text = title
        tvMessage.text = message
        btnPositive.text = positiveText

        if (negativeText != null) {
            btnNegative.text = negativeText
            btnNegative.visibility = View.VISIBLE
        } else {
            btnNegative.visibility = View.GONE
        }

        if (customView != null) {
            customContainer.addView(customView)
            customContainer.visibility = View.VISIBLE
        }

        val alertDialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        alertDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnPositive.setOnClickListener {
            onPositive?.invoke()
            alertDialog.dismiss()
        }

        btnNegative.setOnClickListener {
            alertDialog.dismiss()
        }

        alertDialog.show()
        KeyDialogControls.compactWindow(requireContext(), alertDialog.window)
    }

    private class MessageAdapter(
        context: Context,
        private val onItemClick: (Message) -> Unit,
        private val onDelete: (Message) -> Unit
    ) : RecyclerView.Adapter<MessageAdapter.ViewHolder>() {
        private var messages: List<Message> = emptyList()

        /** The holder whose delete button is currently revealed; at most one at a time. */
        var openHolder: ViewHolder? = null
            private set
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        private val previewMarkwon = Markwon.builder(context)
            .usePlugin(SoftBreakAddsNewLinePlugin.create())
            .usePlugin(object : io.noties.markwon.AbstractMarkwonPlugin() {
                override fun configureTheme(builder: io.noties.markwon.core.MarkwonTheme.Builder) {
                    // 列表标题用粗体区分，字号保持与预览正文一致。
                    builder.headingTextSizeMultipliers(floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f))
                }
            })
            .build()

        fun submitList(list: List<Message>) {
            messages = list
            notifyDataSetChanged()
        }

        fun messageAt(position: Int): Message? = messages.getOrNull(position)

        fun removeMessage(id: String) {
            val index = messages.indexOfFirst { it.messageId == id }
            if (index < 0) return
            messages = messages.filterNot { it.messageId == id }
            notifyItemRemoved(index)
        }

        fun markOpen(holder: ViewHolder?) {
            openHolder = holder
        }

        /** Slides any revealed card back and clears the open state. */
        fun closeReveal() {
            openHolder?.let { it.contentView.animate().translationX(0f).setDuration(180).start() }
            openHolder = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_message, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(messages[position])
        }

        override fun getItemCount(): Int = messages.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val contentView: View = itemView.findViewById(R.id.cardContent)
            private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
            private val tvBody: TextView = itemView.findViewById(R.id.tvBody)
            private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
            private val ivThumbnail: android.widget.ImageView = itemView.findViewById(R.id.ivThumbnail)
            private val btnDeleteReveal: View = itemView.findViewById(R.id.btnDeleteReveal)

            fun bind(message: Message) {
                // Recycled views must start hidden regardless of their previous swipe state.
                contentView.translationX = 0f
                if (openHolder === this@ViewHolder) openHolder = null
                tvTitle.text = message.title ?: "BarkA"
                previewMarkwon.setMarkdown(tvBody, message.body.orEmpty())
                // 预览中的链接也由整张卡片打开详情，避免抢占列表点击。
                tvBody.movementMethod = null
                tvBody.isClickable = false
                tvBody.isLongClickable = false
                tvTime.text = dateFormat.format(message.timestamp)
                
                val thumbnailRequest = message.messageId + ":" + message.image.orEmpty()
                ivThumbnail.tag = thumbnailRequest
                // Show thumbnail if image exists
                if (!message.image.isNullOrEmpty()) {
                    val imageContent = message.image
                    val isUrl = imageContent.startsWith("http://") || imageContent.startsWith("https://")
                    
                    if (isUrl) {
                        ivThumbnail.visibility = View.VISIBLE
                        ivThumbnail.setImageResource(android.R.drawable.ic_menu_gallery)
                        // Load URL thumbnail in background
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            try {
                                val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    val url = java.net.URL(imageContent)
                                    val connection = url.openConnection() as java.net.HttpURLConnection
                                    connection.connectTimeout = 5000
                                    connection.readTimeout = 5000
                                    connection.instanceFollowRedirects = true
                                    connection.setRequestProperty("User-Agent", "Accnotify/1.0")
                                    connection.setRequestProperty("Accept", "image/*,*/*")
                                    connection.doInput = true
                                    connection.connect()
                                    if (connection.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                                        val input = connection.inputStream
                                        val result = android.graphics.BitmapFactory.decodeStream(input)
                                        input.close()
                                        connection.disconnect()
                                        result
                                    } else {
                                        connection.disconnect()
                                        null
                                    }
                                }
                                if (bitmap != null && ivThumbnail.tag == thumbnailRequest) {
                                    ivThumbnail.setImageBitmap(bitmap)
                                }
                            } catch (_: Exception) {}
                        }
                    } else {
                        try {
                            val base64Data = if (imageContent.contains(",")) {
                                imageContent.substringAfter(",")
                            } else {
                                imageContent
                            }
                            val imageBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                            val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                            if (bitmap != null) {
                                ivThumbnail.setImageBitmap(bitmap)
                                ivThumbnail.visibility = View.VISIBLE
                            } else {
                                ivThumbnail.visibility = View.GONE
                            }
                        } catch (e: Exception) {
                            ivThumbnail.visibility = View.GONE
                        }
                    }
                } else {
                    ivThumbnail.visibility = View.GONE
                }
                
                // 点击查看详情；卡片处于露出状态时先关闭，不打开详情。
                contentView.setOnClickListener {
                    if (contentView.translationX != 0f) {
                        closeReveal()
                    } else {
                        onItemClick(message)
                    }
                }
                btnDeleteReveal.setOnClickListener { onDelete(message) }
            }
        }
    }
}
