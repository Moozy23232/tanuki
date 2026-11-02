package com.tanuki.browser

import android.app.AppOpsManager
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.*
import androidx.compose.ui.graphics.toArgb
import com.tanuki.R
import com.tanuki.ui.tanukiColorScheme
import kotlinx.coroutines.*
import kotlin.math.roundToInt

/** A real, bounded Android overlay. Owns the window, never the document or AI permission. */
internal class BrowserOverlayWindow(
    private val serviceContext: Context,
    private val controller: AgentBrowserController,
    private val onClose: () -> Unit,
    private val onOpenFull: () -> Unit,
) {
    private val context = if (Build.VERSION.SDK_INT >= 30) {
        val display = serviceContext.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        serviceContext.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    } else serviceContext
    private val windows = context.getSystemService(WindowManager::class.java)
    private val appOps = context.getSystemService(AppOpsManager::class.java)
    private val preferences = context.getSharedPreferences("browser-overlay-geometry", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var colors = tanukiColorScheme(isDark())
    private var shown = false
    private var closed = false
    private var collapsed = false
    private var showingTabs = false
    private var mounted: WebView? = null
    private var panelKey = ""
    private var lastUrl: String? = null
    private var lastTab: String? = null
    private var lastNotice = controller.state.value.notice
    private var layoutDensity = density()
    private var imeTop: Int? = null
    private var pendingAddressIme = false
    private val fonts = java.util.IdentityHashMap<TextView, Float>()
    private val buttons = mutableListOf<Button>()
    private val root = object : FrameLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { releaseFocus(); return false }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) focusWindow(true)
            return super.dispatchTouchEvent(event)
        }
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    if (showingTabs) { showingTabs = false; render(controller.state.value) }
                    else { releaseFocus(); if (!collapsed) toggleCollapsed() }
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (hasWindowFocus) post { showPendingAddressIme() }
        }
        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)
            post { if (!closed) this@BrowserOverlayWindow.configurationChanged() }
        }
        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            if (shown && !closed) post { if (!closed) onClose() }
        }
    }.apply {
        contentDescription = "浏览器系统悬浮窗"
        clipChildren = true; clipToPadding = true; clipToOutline = true
        filterTouchesWhenObscured = true
    }
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val title = label("浏览器", 14f).apply {
        setTypeface(typeface, Typeface.BOLD); setSingleLine(); ellipsize = TextUtils.TruncateAt.END
        contentDescription = "拖动浏览器悬浮窗"
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(4), 0)
    }
    private val collapseButton = button("收起", "收起浏览器悬浮窗", ::toggleCollapsed)
    private val addressRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(4), dp(4), dp(4)) }
    private val address = EditText(context).apply {
        fonts[this] = 14f; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setSingleLine()
        hint = "搜索或输入网址"; contentDescription = "悬浮窗网址输入框"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        setPadding(dp(12), 0, dp(8), 0)
        setOnClickListener { requestAddressIme() }
        setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_GO || event?.let { it.keyCode == KeyEvent.KEYCODE_ENTER && it.action == KeyEvent.ACTION_UP } == true) { navigate(); true } else false
        }
    }
    private val statusRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(4), 0) }
    private val status = label("", 12f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val controlButton = button("收回", "收回 AI 浏览器权限") {
        if (controller.state.value.authorizedClient != null) controller.takeControl() else controller.grantConnection()
    }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
    private val body = FrameLayout(context).apply { clipChildren = true; clipToPadding = true }
    private val document = FrameLayout(context).apply { clipChildren = true; clipToPadding = true }
    private val empty = label("搜索或输入网址开始浏览\n\n拖动顶部移动窗口\n拖动左下角或右下角调整大小", 14f).apply {
        gravity = Gravity.CENTER; setPadding(dp(24), dp(12), dp(24), dp(12))
    }
    private val panel = FrameLayout(context).apply { visibility = View.GONE; isClickable = true; isFocusable = true }
    private val footer = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val back = button("后退", "悬浮窗后退", controller::back)
    private val tabButton = button("页面", "悬浮窗页面总览") {
        releaseFocus(); showingTabs = !showingTabs; render(controller.state.value)
    }
    private val leftResizeHandle = resizeGrip("拖动左下角调整悬浮窗大小", left = true)
    private val rightResizeHandle = resizeGrip("拖动右下角调整悬浮窗大小", left = false)
    private fun resizeGrip(description: String, left: Boolean) = ImageView(context).apply {
        minimumWidth = dp(48); minimumHeight = dp(48); contentDescription = description
        scaleType = ImageView.ScaleType.CENTER; setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnLongClickListener { Toast.makeText(context, "拖动左下角或右下角可调整窗口宽度和高度", Toast.LENGTH_SHORT).show(); true }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        tag = if (left) "left" else "right"
    }
    private var expandedFrame = initialFrame()
    @Suppress("RtlHardcoded") // WindowManager x/y are physical display coordinates.
    private val params = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        gravity = Gravity.TOP or Gravity.LEFT
        // Geometry uses physical screen coordinates; safe system/cutout insets are applied by us.
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        title = "${context.getString(R.string.app_name)} browser overlay"
    }
    private val permissionListener = AppOpsManager.OnOpChangedListener { operation, packageName ->
        if (operation == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW && packageName == context.packageName)
            root.post { if (!closed && !Settings.canDrawOverlays(context)) onClose() }
    }

    init {
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val previous = imeTop
                val covered = insets.getInsets(WindowInsets.Type.ime()).bottom
                if (!insets.isVisible(WindowInsets.Type.ime())) imeTop = null
                else if (covered > 0 && view.height > 0) {
                    val location = IntArray(2); view.getLocationOnScreen(location)
                    imeTop = location[1] + view.height - covered
                }
                // Insets are relative to this bounded window, not the whole display. Retain the
                // known keyboard edge after moving above it, until the IME actually disappears.
                if (previous != imeTop) view.post { if (!closed) updateFrame() }
            }
            insets
        }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        column.addView(header, LinearLayout.LayoutParams(-1, dp(56)))
        header.addView(title, LinearLayout.LayoutParams(0, -1, 1f))
        header.addView(collapseButton, LinearLayout.LayoutParams(dp(56), -1))
        header.addView(button("全屏", "打开完整浏览器", onOpenFull), LinearLayout.LayoutParams(dp(56), -1))
        header.addView(button("关闭", "关闭浏览器悬浮窗", onClose), LinearLayout.LayoutParams(dp(56), -1))
        column.addView(addressRow, LinearLayout.LayoutParams(-1, dp(56)))
        addressRow.addView(address, LinearLayout.LayoutParams(0, -1, 1f))
        addressRow.addView(button("前往", "悬浮窗前往网址", ::navigate), LinearLayout.LayoutParams(dp(56), -1))
        column.addView(statusRow, LinearLayout.LayoutParams(-1, -2))
        statusRow.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        statusRow.addView(controlButton, LinearLayout.LayoutParams(dp(56), dp(48)))
        column.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        column.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(empty, FrameLayout.LayoutParams(-1, -1))
        body.addView(document, FrameLayout.LayoutParams(-1, -1))
        body.addView(panel, FrameLayout.LayoutParams(-1, -1))
        column.addView(footer, LinearLayout.LayoutParams(-1, dp(48)))
        footer.addView(leftResizeHandle, LinearLayout.LayoutParams(dp(48), -1))
        footer.addView(back, LinearLayout.LayoutParams(dp(56), -1))
        footer.addView(tabButton, LinearLayout.LayoutParams(0, -1, 1f))
        footer.addView(button("新建", "悬浮窗新建页面") { showingTabs = false; controller.newTab() }, LinearLayout.LayoutParams(dp(56), -1))
        footer.addView(rightResizeHandle, LinearLayout.LayoutParams(dp(48), -1))
        installGesture(title)
        installGesture(leftResizeHandle, resize = true, left = true)
        installGesture(rightResizeHandle, resize = true)
        applyTheme()
    }

    fun revealPage() {
        if (closed) return
        if (collapsed) toggleCollapsed()
        showingTabs = false
        render(controller.state.value)
    }
    fun show(): Boolean {
        if (closed || !Settings.canDrawOverlays(context)) return false
        if (shown) return true
        updateFrame(publish = false)
        return try {
            windows.addView(root, params)
            shown = true
            appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, context.packageName, permissionListener)
            controller.setOverlayFrame(actualFrame(), collapsed)
            scope.launch { controller.state.collect { state -> if (state.closed) onClose() else render(state) } }
            true
        } catch (_: Exception) { close(); false }
    }
    fun close() {
        if (closed) return
        closed = true; persistGeometry(); scope.cancel()
        runCatching { appOps.stopWatchingMode(permissionListener) }
        if (shown) runCatching { windows.removeViewImmediate(root) }
        shown = false
        mounted?.let { controller.park(it, document) }; mounted = null
        document.removeAllViews()
        controller.setOverlayFrame(null)
    }
    fun configurationChanged() {
        if (closed) return
        val newDensity = density()
        if (newDensity != layoutDensity) {
            val factor = newDensity / layoutDensity
            fun scale(value: Int) = (value * factor).roundToInt()
            fun resizeChrome(view: View) {
                view.layoutParams?.let { p ->
                    if (p.width > 0) p.width = scale(p.width)
                    if (p.height > 0) p.height = scale(p.height)
                    if (p is ViewGroup.MarginLayoutParams) p.setMargins(scale(p.leftMargin), scale(p.topMargin), scale(p.rightMargin), scale(p.bottomMargin))
                    view.layoutParams = p
                }
                view.setPadding(scale(view.paddingLeft), scale(view.paddingTop), scale(view.paddingRight), scale(view.paddingBottom))
                view.minimumWidth = scale(view.minimumWidth); view.minimumHeight = scale(view.minimumHeight)
                if (view is ViewGroup && view !is WebView) for (i in 0 until view.childCount) resizeChrome(view.getChildAt(i))
            }
            resizeChrome(column)
            expandedFrame = BrowserOverlayFrame(scale(expandedFrame.x), scale(expandedFrame.y), scale(expandedFrame.width), scale(expandedFrame.height))
            layoutDensity = newDensity
            buttons.forEach { it.setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, TypedValue.COMPLEX_UNIT_SP) }
        }
        applyTheme(); updateFrame()
    }
    private fun render(state: BrowserState) {
        if (!shown || closed) return
        if (state.notice != lastNotice) {
            lastNotice = state.notice
            state.notice?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        }
        if (state.activeTab != lastTab || state.url != lastUrl) {
            if (!address.hasFocus()) address.setText(state.url)
            lastUrl = state.url; lastTab = state.activeTab
        }
        title.text = when {
            !collapsed -> "浏览器"
            state.takeoverRequested -> "等待接管"
            state.authorizedClient != null -> "浏览器 · AI"
            else -> "浏览器 · 你"
        }
        status.text = when {
            state.takeoverRequested -> "AI 已暂停 · 等待接管选择"
            state.authorizedClient != null -> "AI 可操作 · 点网页收回"
            else -> "你在操作 · AI 无权读取"
        }
        controlButton.text = if (state.authorizedClient != null) "收回" else "交回"
        controlButton.contentDescription = if (state.authorizedClient != null) "收回 AI 浏览器权限" else "交回 AI 浏览器权限"
        controlButton.isEnabled = !state.rendererFailed && !state.takeoverRequested
        back.isEnabled = state.canGoBack
        tabButton.text = "页面 ${state.tabs.size}"
        progress.progress = state.loadProgress
        progress.visibility = if (!collapsed && state.loading) View.VISIBLE else View.GONE
        if (!state.rendererFailed && state.url.isNotEmpty()) {
            val next = controller.webView
            if (mounted !== next || next.parent !== document) {
                mounted?.let { controller.park(it, document) }
                (next.parent as? ViewGroup)?.removeView(next)
                document.removeAllViews()
                next.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                document.addView(next, FrameLayout.LayoutParams(-1, -1)); mounted = next
                next.onResume()
            }
            empty.visibility = View.GONE
        } else {
            mounted?.let { controller.park(it, document) }; mounted = null
            empty.visibility = View.VISIBLE
            empty.text = if (state.rendererFailed) "网页渲染已中断\n请在完整浏览器中新建页面" else "搜索或输入网址开始浏览\n\n拖动顶部移动窗口\n拖动右下角调整大小"
        }
        renderPanel(state)
    }
    private fun renderPanel(state: BrowserState) {
        val key = when {
            state.takeoverRequested -> "takeover"
            state.downloadRequest != null -> "download:${state.downloadRequest.url}"
            showingTabs -> "tabs:${state.tabs}"
            state.error != null && !state.rendererFailed -> "error:${state.error}"
            else -> ""
        }
        if (key == panelKey) return
        panelKey = key
        fun forget(view: View) {
            if (view is ViewGroup) for (index in 0 until view.childCount) forget(view.getChildAt(index))
            if (view is TextView) fonts.remove(view)
            if (view is Button) buttons.remove(view)
        }
        for (index in 0 until panel.childCount) forget(panel.getChildAt(index))
        panel.removeAllViews(); panel.visibility = if (key.isEmpty()) View.GONE else View.VISIBLE
        if (key.isEmpty()) return
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val items = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        scroll.addView(items); panel.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        fun text(value: String) { items.addView(label(value, 14f).apply { setPadding(0, dp(8), 0, dp(8)) }) }
        fun action(value: String, action: () -> Unit) { items.addView(button(value, value, action), LinearLayout.LayoutParams(-1, dp(48))) }
        when {
            state.takeoverRequested -> {
                text("收回 AI 浏览器权限？")
                text("AI 已暂停，这次点击尚未执行。收回后由你操作，AI 不能读取或控制网页。")
                action("收回权限") { controller.resolveTakeover(true) }
                action("继续交给 AI") { controller.resolveTakeover(false) }
            }
            state.downloadRequest != null -> {
                val request = state.downloadRequest
                text("下载文件？"); text(request.name)
                text("文件将保存在浏览器中，可在完整浏览器的「下载与截图」查看进度和导出。")
                action("确认下载") { controller.acceptDownload(request) }
                action("取消下载") { controller.dismissDownload() }
            }
            state.error != null && !state.rendererFailed && !showingTabs -> {
                text("网页未能打开"); text(state.error)
                action("重新加载") { controller.reload() }
                action("打开完整浏览器", onOpenFull)
            }
            else -> {
                text("${state.tabs.size} 个页面")
                state.tabs.forEach { tab ->
                    action((if (tab.id == state.activeTab) "当前 · " else "") + tab.title.ifBlank { "新页面" }) {
                        showingTabs = false; controller.selectTab(tab.id); render(controller.state.value)
                    }
                }
                action("返回网页") { showingTabs = false; render(controller.state.value) }
            }
        }
    }
    private fun navigate() {
        val value = address.text.toString().take(8192)
        if (value.isNotBlank()) { showingTabs = false; releaseFocus(); controller.navigateInput(value) }
    }
    private fun toggleCollapsed() {
        if (closed) return
        collapsed = !collapsed
        collapseButton.text = if (collapsed) "展开" else "收起"
        collapseButton.contentDescription = if (collapsed) "展开浏览器悬浮窗" else "收起浏览器悬浮窗"
        listOf(addressRow, statusRow, body, footer).forEach { it.visibility = if (collapsed) View.GONE else View.VISIBLE }
        if (collapsed) { releaseFocus(); progress.visibility = View.GONE }
        updateFrame(); render(controller.state.value)
    }
    private fun focusWindow(focusable: Boolean) {
        if (!shown || closed) return
        val flags = if (focusable) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (flags != params.flags) { params.flags = flags; runCatching { windows.updateViewLayout(root, params) }.onFailure { onClose() } }
    }
    private fun requestAddressIme() {
        if (!shown || closed || collapsed) return
        pendingAddressIme = true
        focusWindow(true); address.requestFocus()
        address.post { showPendingAddressIme() }
    }
    private fun showPendingAddressIme() {
        // FLAG_NOT_FOCUSABLE changes are asynchronous. A tap must not lose its IME request
        // just because the window has not received focus yet; never request it for a drag.
        if (!pendingAddressIme || closed || collapsed || !address.hasFocus() || !address.hasWindowFocus()) return
        if (context.getSystemService(InputMethodManager::class.java).showSoftInput(address, InputMethodManager.SHOW_IMPLICIT)) pendingAddressIme = false
    }
    private fun releaseFocus() {
        pendingAddressIme = false
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
        root.findFocus()?.clearFocus(); focusWindow(false)
    }
    private fun installGesture(view: View, resize: Boolean = false, left: Boolean = false) {
        var startX = 0f; var startY = 0f; var start = expandedFrame
        var dragged = false
        view.setOnClickListener { if (resize) Toast.makeText(context, "拖动左下角或右下角调整大小", Toast.LENGTH_SHORT).show() }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    releaseFocus(); startX = event.rawX; startY = event.rawY; start = actualFrame(); dragged = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startX).roundToInt(); val dy = (event.rawY - startY).roundToInt()
                    dragged = dragged || kotlin.math.abs(dx) + kotlin.math.abs(dy) > ViewConfiguration.get(context).scaledTouchSlop
                    val area = availableSpace()
                    val next = if (resize) BrowserOverlayGeometry.resize(start, dx, dy, area, dp(280), dp(300), left)
                        else BrowserOverlayGeometry.move(start, dx, dy, area, dp(280), if (collapsed) dp(56) else dp(300))
                    expandedFrame = if (collapsed) expandedFrame.copy(x = next.x, y = next.y, width = next.width) else next
                    updateFrame(); true
                }
                MotionEvent.ACTION_UP -> { persistGeometry(); if (!dragged) view.performClick(); true }
                MotionEvent.ACTION_CANCEL -> { persistGeometry(); true }
                else -> true
            }
        }
    }
    private fun updateFrame(publish: Boolean = true) {
        if (closed) return
        val requested = if (collapsed) expandedFrame.copy(height = dp(56)) else expandedFrame
        val fitted = BrowserOverlayGeometry.fit(requested, availableSpace(includeKeyboard = true), dp(280), if (collapsed) dp(56) else dp(300))
        val changed = fitted != actualFrame()
        params.x = fitted.x; params.y = fitted.y; params.width = fitted.width; params.height = fitted.height
        if (!collapsed && imeTop == null) expandedFrame = fitted
        if (shown) {
            if (changed) try { windows.updateViewLayout(root, params) } catch (_: Exception) { onClose(); return }
            if (publish && (controller.state.value.overlayFrame != fitted || controller.state.value.overlayCollapsed != collapsed))
                controller.setOverlayFrame(fitted, collapsed)
        }
    }
    private fun actualFrame() = BrowserOverlayFrame(params.x, params.y, params.width, params.height)
    private fun initialFrame(): BrowserOverlayFrame {
        val area = availableSpace()
        fun saved(key: String, fallback: Int) = runCatching { (preferences.getFloat(key, fallback / density()) * density()).roundToInt() }.getOrDefault(fallback)
        val width = saved("width", minOf(dp(400), (area.width * .86f).roundToInt()))
        val height = saved("height", minOf(dp(520), (area.height * .68f).roundToInt()))
        return BrowserOverlayGeometry.fit(BrowserOverlayFrame(saved("x", area.left + area.width - width - dp(12)),
            saved("y", area.top + dp(40)), width, height), area, dp(280), dp(300))
    }
    private fun persistGeometry() {
        preferences.edit().putFloat("x", expandedFrame.x / density()).putFloat("y", expandedFrame.y / density())
            .putFloat("width", expandedFrame.width / density()).putFloat("height", expandedFrame.height / density()).apply()
    }
    private fun availableSpace(includeKeyboard: Boolean = false): BrowserOverlaySpace {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windows.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val rect = metrics.bounds
            val bottom = minOf(rect.bottom - insets.bottom, if (includeKeyboard) imeTop ?: Int.MAX_VALUE else Int.MAX_VALUE)
            return BrowserOverlaySpace(rect.left + insets.left, rect.top + insets.top,
                (rect.width() - insets.left - insets.right).coerceAtLeast(1), (bottom - rect.top - insets.top).coerceAtLeast(1))
        }
        @Suppress("DEPRECATION") val display = windows.defaultDisplay
        val rect = Rect(); @Suppress("DEPRECATION") display.getRectSize(rect)
        return BrowserOverlaySpace(0, dp(24), rect.width().coerceAtLeast(1), (rect.height() - dp(48)).coerceAtLeast(1))
    }
    private fun label(value: String, size: Float) = TextView(context).apply {
        fonts[this] = size; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); text = value; setTextColor(colors.onSurface.toArgb())
    }
    private fun button(value: String, description: String, action: () -> Unit) = Button(context).apply {
        fonts[this] = 14f; buttons.add(this)
        text = value; contentDescription = description; isAllCaps = false; minWidth = dp(48); minHeight = dp(48)
        minimumWidth = dp(48); minimumHeight = dp(48); setPadding(dp(4), 0, dp(4), 0)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        stateListAnimator = null; elevation = 0f; translationZ = 0f
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, TypedValue.COMPLEX_UNIT_SP)
        setOnClickListener { action() }; tintButton(this)
    }
    private fun tintButton(button: Button) {
        button.setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(colors.primary.toArgb(), colors.onSurfaceVariant.copy(alpha = .5f).toArgb())))
        val shape = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(android.graphics.Color.TRANSPARENT) }
        button.background = RippleDrawable(ColorStateList.valueOf(colors.primary.copy(alpha = .12f).toArgb()), shape, null)
    }
    private fun applyTheme() {
        colors = tanukiColorScheme(isDark())
        root.background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(this@BrowserOverlayWindow.colors.surface.toArgb()); setStroke(dp(1), this@BrowserOverlayWindow.colors.outlineVariant.toArgb()) }
        header.setBackgroundColor(colors.surfaceVariant.toArgb()); panel.setBackgroundColor(colors.surface.toArgb())
        document.setBackgroundColor(colors.surface.toArgb())
        address.background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(this@BrowserOverlayWindow.colors.surfaceVariant.toArgb()); setStroke(dp(1), this@BrowserOverlayWindow.colors.outlineVariant.toArgb()) }
        fonts.forEach { (view, size) -> view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size); view.setTextColor(colors.onSurface.toArgb()) }
        buttons.forEach(::tintButton)
        address.setHintTextColor(colors.onSurfaceVariant.toArgb()); status.setTextColor(colors.onSurfaceVariant.toArgb())
        progress.progressTintList = ColorStateList.valueOf(colors.primary.toArgb())
        listOf(leftResizeHandle to true, rightResizeHandle to false).forEach { (handle, left) ->
        handle.setImageDrawable(object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.onSurfaceVariant.toArgb(); strokeWidth = dp(2).toFloat(); strokeCap = Paint.Cap.ROUND }
            override fun getIntrinsicWidth() = dp(24)
            override fun getIntrinsicHeight() = dp(24)
            override fun draw(canvas: Canvas) {
                canvas.save()
                if (left) canvas.scale(-1f, 1f, bounds.exactCenterX(), 0f)
                for (offset in intArrayOf(5, 11, 17)) canvas.drawLine(bounds.right - dp(offset).toFloat(), bounds.bottom - dp(3).toFloat(), bounds.right - dp(3).toFloat(), bounds.bottom - dp(offset).toFloat(), paint)
                canvas.restore()
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
            @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
        })
        }
    }
    private fun isDark() = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private fun density() = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density()).roundToInt().coerceAtLeast(1)
}
