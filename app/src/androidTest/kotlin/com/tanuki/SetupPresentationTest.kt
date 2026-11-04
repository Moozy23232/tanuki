package com.tanuki

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.tanuki.runtime.*
import com.tanuki.storage.WorkspaceStorageState
import com.tanuki.ui.GlassNavigationBar
import com.tanuki.ui.TanukiGlyph
import com.tanuki.ui.TanukiIcon
import com.tanuki.ui.TanukiTheme
import com.tanuki.ui.TanukiUi
import com.tanuki.ui.TanukiUiCallbacks
import com.tanuki.ui.TanukiUiModel
import com.tanuki.update.Release
import com.tanuki.web.DshWebView
import com.tanuki.web.WebViewSupport
import com.tanuki.web.WebViewSupportState
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Fixture-only: inventory/model/callbacks. Actual: MainActivity DshWebView, inline HTML, fullscreen, screenshots, Theme.Tanuki. */
class SetupPresentationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun requireIsolation() = check(context.packageName.endsWith(".workspacecheck")) {
        "Use scripts/workspace-verification.init.gradle for UI test isolation"
    }
    private fun launch() = requireIsolation().let {
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
    }
    private fun browserOf(activity: MainActivity): DshWebView =
        MainActivity::class.java.getDeclaredField("browser").apply { isAccessible = true }.get(activity) as DshWebView
    private fun cancelSourceTests() {
        runCatching { (context.applicationContext as TanukiApp).runtime.components.sourceTests.values.forEach { it.cancel() } }
    }
    private fun cleanup(activity: MainActivity) {
        cancelSourceTests(); runCatching { instrumentation.runOnMainSync { activity.finish() } }; SystemClock.sleep(200)
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        if (root.packageName?.toString() != context.packageName) return emptyList()
        val all = ArrayList<AccessibilityNodeInfo>(); val pending = ArrayDeque<AccessibilityNodeInfo>(); pending.add(root)
        while (pending.isNotEmpty() && all.size < 1000) {
            val node = pending.removeFirst()
            if (!node.refresh()) continue
            all.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(pending::addLast)
        }
        return all
    }
    private fun matches(node: AccessibilityNodeInfo, text: String) =
        node.text?.toString() == text || node.contentDescription?.toString() == text
    private fun find(text: String): AccessibilityNodeInfo {
        var revealRequested = false
        var backwards = false
        repeat(50) {
            val current = nodes()
            current.firstOrNull { matches(it, text) && it.isVisibleToUser }?.let { return it }
            if (!revealRequested && current.firstOrNull { matches(it, text) }
                    ?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) == true) {
                revealRequested = true
                SystemClock.sleep(150)
                return@repeat
            }
            // 离屏节点可能不在当前无障碍树中；到达边界后反向查找，不一直向下滚。
            val action = if (backwards) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            val scrollable = current.firstOrNull { it.isScrollable && it.actionList.any { a -> a.id == action } }
            if (scrollable?.performAction(action) != true) backwards = !backwards
            SystemClock.sleep(150)
        }
        screenshot("setup-visible-control-failure.png").recycle()
        throw AssertionError("Visible control not found: $text")
    }
    private fun click(text: String) {
        find(text)
        repeat(8) {
            instrumentation.waitForIdleSync()
            for (candidate in nodes().filter { matches(it, text) && it.isVisibleToUser }.asReversed()) {
                var node: AccessibilityNodeInfo? = candidate
                while (node != null && !node.isClickable) node = node.parent
                if (node?.isEnabled == true && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    SystemClock.sleep(200)
                    return
                }
            }
            SystemClock.sleep(120)
        }
        screenshot("setup-control-failure.png").recycle()
        File(context.cacheDir,"setup-control-failure-tree.txt").writeText(nodes().joinToString("\n") {
            val bounds=Rect().also(it::getBoundsInScreen)
            "${it.text} ${it.contentDescription} clickable=${it.isClickable} enabled=${it.isEnabled} visible=${it.isVisibleToUser} bounds=$bounds"
        })
        error("Control did not accept click: $text")
    }
    private fun tap(text: String) = click(text)

    private fun screenshot(name: String): Bitmap {
        val image = instrumentation.uiAutomation.takeScreenshot() ?: error("screenshot unavailable")
        File(context.cacheDir, name).outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return image
    }
    private fun differ(a: Bitmap, b: Bitmap): Boolean {
        if (a.width != b.width || a.height != b.height) return true
        var changed = 0
        for (x in 0 until a.width step 40) for (y in 0 until a.height step 40) if (a.getPixel(x, y) != b.getPixel(x, y)) changed++
        return changed > 10
    }
    private fun hasMultipleColors(image: Bitmap, bounds: Rect): Boolean {
        val left = bounds.left.coerceIn(0, image.width - 1); val top = bounds.top.coerceIn(0, image.height - 1)
        val right = bounds.right.coerceIn(left + 1, image.width); val bottom = bounds.bottom.coerceIn(top + 1, image.height)
        val colors = HashSet<Int>()
        for (x in 0..20) for (y in 0..20) colors += image.getPixel(left + (right - left - 1) * x / 20, top + (bottom - top - 1) * y / 20)
        return colors.size > 1
    }
    private fun boundsOf(view: View): Rect {
        val location = IntArray(2); view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }
    private fun isNight(config: Configuration) =
        (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun mount(activity: MainActivity, state: MutableState<TanukiUiModel>, rec: Recorder,
                      browser: WebView, night: Boolean, fontScale: Float,
                      filesPage: @Composable (Modifier, Dp) -> Unit = { _, _ -> }) {
        val config = Configuration(activity.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        }
        val density = activity.resources.displayMetrics.density
        instrumentation.runOnMainSync {
            (browser.parent as? ViewGroup)?.removeView(browser)
            activity.setContent {
                CompositionLocalProvider(LocalConfiguration provides config, LocalDensity provides Density(density, fontScale)) {
                    TanukiTheme { Surface(Modifier.fillMaxSize()) {
                        val cb = remember { callbacks(state, rec, activity) }
                        TanukiUi(state.value, cb, browser, filesPage = filesPage)
                    } }
                }
            }
        }
        instrumentation.waitForIdleSync(); SystemClock.sleep(400)
    }

    private fun callbacks(state: MutableState<TanukiUiModel>, rec: Recorder, activity: MainActivity,
                          windowMode: com.tanuki.ui.DshWindowMode = com.tanuki.ui.DshWindowMode(activity.window)) = TanukiUiCallbacks(
        command = { rec.commands += it }, install = { rec.installs += it }, checkUpdates = {}, importFiles = {},
        measureStorage = {}, toggleLegacy = {}, toggleBlob = {}, showNotices = {}, updateWebView = {},
        webViewSettings = {}, appSettings = {}, notifications = {}, connectDsh = {},
        selectSource = { component, id ->
            rec.sourceSelections += component to id
            if (component == RuntimeComponent.UBUNTU) state.value = state.value.copy(
                components = state.value.components.copy(ubuntuSource = id, ubuntuAutomatic = id == "auto"))
            if (component == RuntimeComponent.NODE) state.value = state.value.copy(
                components = state.value.components.copy(nodeSource = id, nodeAutomatic = id == "auto"))
        },
        selectVersion = { component, version ->
            rec.versionSelections += component to version
            if (component == RuntimeComponent.UBUNTU) state.value = state.value.copy(
                components = state.value.components.copy(selectedUbuntuVersion = version))
            if (component == RuntimeComponent.NODE) state.value = state.value.copy(
                components = state.value.components.copy(selectedNodeVersion = version))
        },
        setFullscreen = { full -> rec.fullscreen += full; windowMode.setFullscreen(full) }, testSources = {}, cancelSourceTests = {},
        refreshWorkspaceStorage = {}, saveWorkspaceCapacity = {}
    )
    private fun model(snapshot: Snapshot = Snapshot(), support: WebViewSupport = supported()) = TanukiUiModel(
        snapshot = snapshot, support = support, versions = emptyList(), recentLogs = emptyList(), storageUsage = "fixture",
        deviceInfo = "fixture", backgroundResult = null, legacyShim = false, blobExport = false, versionName = "fixture",
        freeBytes = 0L, components = ComponentInventory(), ubuntuSources = UBUNTU_SOURCES, nodeSources = NODE_SOURCES,
        ubuntuSpeed = SourceSpeedSnapshot(measuredAtMillis = 1L), nodeSpeed = SourceSpeedSnapshot(measuredAtMillis = 1L),
        nodeRequirement = "", workspaceStorage = WorkspaceStorageState(), tools = RequiredToolsStatus())
    private fun supported() = WebViewSupport(WebViewSupportState.SUPPORTED, "test", "130.0.0.0", true, true)
    private fun runningSnapshot() = Snapshot(environment = EnvironmentState.READY, health = HealthState.HEALTHY,
        service = ServiceState.RUNNING, web = WebState.CONNECTED, webHealthy = true, update = UpdateState.CURRENT)

    private fun awaitProbe(browser: DshWebView) {
        val deadline = System.currentTimeMillis() + 15_000
        while (browser.support.value.state in setOf(WebViewSupportState.UNCHECKED, WebViewSupportState.CHECKING) &&
            System.currentTimeMillis() < deadline) SystemClock.sleep(50)
        val measured = browser.support.value
        assertNotEquals("capability probe did not finish", WebViewSupportState.UNCHECKED, measured.state)
        assertNotEquals("capability probe did not finish", WebViewSupportState.CHECKING, measured.state)
        assertNotEquals("capability probe failed", WebViewSupportState.FAILED, measured.state)
        assertNotNull(measured.subgrid); assertNotNull(measured.lightDark)
    }

    private fun eval(view: WebView, script: String): String? {
        val latch = CountDownLatch(1); var result: String? = null
        instrumentation.runOnMainSync { view.evaluateJavascript(script) { value -> result = value; latch.countDown() } }
        return if (latch.await(5, TimeUnit.SECONDS)) result else null
    }
    private fun evalBoolean(view: WebView, expression: String): Boolean? = eval(view, expression)?.toBooleanStrictOrNull()
    private fun evalString(view: WebView, expression: String): String? =
        runCatching { JSONTokener(eval(view, expression) ?: return null).nextValue() as? String }.getOrNull()
    private fun evalObject(view: WebView, expression: String): JSONObject? {
        val raw = eval(view, "JSON.stringify($expression)") ?: return null
        val inner = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return null
        return runCatching { JSONObject(inner) }.getOrNull()
    }

    private fun loadHtml(view: WebView, html: String, ready: String) {
        instrumentation.runOnMainSync { view.loadDataWithBaseURL(BASE_URL, html, "text/html", "UTF-8", null) }
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) { if (evalBoolean(view, ready) == true) return; SystemClock.sleep(100) }
        error("HTML did not load: $ready")
    }
    private data class Viewport(val w: Int, val h: Int, val marker: String, val timeOrigin: Double)
    private fun viewport(view: WebView): Viewport? {
        val obj = evalObject(view, "({w:document.documentElement.clientWidth,h:document.documentElement.clientHeight," +
            "marker:window.__setupMarker,timeOrigin:performance.timeOrigin})") ?: return null
        if (obj.optInt("w") <= 0 || obj.optInt("h") <= 0 || obj.optString("marker").isBlank()) return null
        return Viewport(obj.getInt("w"), obj.getInt("h"), obj.getString("marker"), obj.getDouble("timeOrigin"))
    }
    private fun awaitViewport(view: WebView, until: (Viewport) -> Boolean = { true }): Viewport {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) { viewport(view)?.let { if (until(it)) return it }; SystemClock.sleep(100) }
        error("WebView viewport condition not met")
    }
    private fun attachedWebView(activity: MainActivity, expected: WebView): WebView? {
        fun walk(view: View): WebView? {
            if (view is WebView && view === expected) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))?.let { return it }
            return null
        }
        return walk(activity.window.decorView)
    }
    private fun awaitAttached(activity: MainActivity, web: WebView) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) { if (attachedWebView(activity, web) === web) return; SystemClock.sleep(100) }
        error("actual WebView was not attached")
    }
    private fun createThemedWebView(dark: Boolean): WebView {
        lateinit var view: WebView
        instrumentation.runOnMainSync {
            val config = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            }
            val wrapper = ContextThemeWrapper(context.createConfigurationContext(config), R.style.Theme_Tanuki)
            view = WebView(wrapper).apply {
                settings.javaScriptEnabled = true
                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING))
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
                measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
                layout(0, 0, 1080, 1920)
            }
        }
        return view
    }

    private class GlassFixture(backdrop: Boolean = true, rtl: Boolean = false, fontScale: Float = 1f) {
        val labels = listOf("工作台", "DSH", "文件", "浏览器")
        val selected = mutableStateOf(0)
        val selections = ArrayList<Int>()
        val useBackdrop = mutableStateOf(backdrop)
        val rtl = mutableStateOf(rtl)
        val fontScale = mutableStateOf(fontScale)
        val narrow = mutableStateOf(false)
        val radius = mutableStateOf(16.dp)
        val bounds = java.util.concurrent.atomic.AtomicReference<Rect>()
    }

    private fun mountGlassNavigation(activity: MainActivity, fixture: GlassFixture) {
        val glyphs = listOf(TanukiGlyph.Home, TanukiGlyph.Terminal, TanukiGlyph.Folder, TanukiGlyph.Browser)
        instrumentation.runOnMainSync {
            activity.setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(activity.resources.displayMetrics.density, fixture.fontScale.value),
                    LocalLayoutDirection provides if (fixture.rtl.value) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    TanukiTheme {
                        val backdrop = rememberLayerBackdrop()
                        Box(Modifier.fillMaxSize()) {
                            // 只录制合成原生条纹，不挂载或捕获任何 WebView；模糊半径由导航层控制。
                            Canvas(Modifier.fillMaxSize().then(
                                if (fixture.useBackdrop.value) Modifier.layerBackdrop(backdrop) else Modifier,
                            )) {
                                val stripe = 6.dp.toPx()
                                for (index in 0..(size.width / stripe).toInt()) {
                                    drawRect(if (index % 2 == 0) Color.Black else Color.White,
                                        Offset(index * stripe, 0f), Size(stripe, size.height))
                                }
                            }
                            GlassNavigationBar(
                                selectedIndex = fixture.selected.value,
                                labels = fixture.labels,
                                onSelect = { fixture.selections += it; fixture.selected.value = it },
                                backdrop = if (fixture.useBackdrop.value) backdrop else null,
                                modifier = Modifier.align(Alignment.BottomCenter)
                                    .widthIn(max = if (fixture.narrow.value) 320.dp else androidx.compose.ui.unit.Dp.Infinity)
                                    .navigationBarsPadding().padding(16.dp)
                                    .onGloballyPositioned { coordinates ->
                                        val r = coordinates.boundsInWindow()
                                        val origin = IntArray(2).also(activity.window.decorView::getLocationOnScreen)
                                        fixture.bounds.set(Rect(r.left.toInt() + origin[0], r.top.toInt() + origin[1],
                                            r.right.toInt() + origin[0], r.bottom.toInt() + origin[1]))
                                    },
                                blurRadius = fixture.radius.value,
                            ) { index, tint -> TanukiIcon(glyphs[index], tint = tint) }
                        }
                    }
                }
            }
        }
        fixture.labels.forEach { find(it) }
        instrumentation.waitForIdleSync(); SystemClock.sleep(700)
    }

    private fun glassTab(label: String): AccessibilityNodeInfo {
        find(label)
        // 页面标题可能与页签同名；逐个检查候选，不能误把非交互标题当作导航。
        for (candidate in nodes().filter { matches(it, label) && it.isVisibleToUser }.asReversed()) {
            var node: AccessibilityNodeInfo? = candidate
            // 已选 Tab 不暴露点击动作；父节点也须刷新，避免读取缓存中的旧选择。
            while (node != null && node.refresh()) {
                if (node.isClickable || node.isSelected) return node
                node = node.parent
            }
        }
        error("Tab has no selectable ancestor: $label")
    }
    private fun glassTabBounds(label: String) = Rect().also(glassTab(label)::getBoundsInScreen)
    private fun assertGlassSelection(fixture: GlassFixture, selected: Int, calls: List<Int>) {
        var actual: Pair<Int, List<Int>>? = null
        // 回调在主线程写入；在同一线程复制后再断言，避免读取可变列表时发生竞争。
        instrumentation.runOnMainSync { actual = fixture.selected.value to fixture.selections.toList() }
        assertEquals("Selected index and exact callback history", selected to calls, actual)
        fixture.labels.forEachIndexed { index, label ->
            assertEquals("Accessibility selection must follow committed state: $label", selected == index, glassTab(label).isSelected)
        }
    }
    private fun assertGlassLabelsVisible(fixture: GlassFixture) {
        val bar = requireNotNull(fixture.bounds.get())
        // 无障碍使用屏幕坐标，不能拿减去系统栏后的应用 DisplayMetrics 高度作为屏幕下界。
        val windowBounds = Rect().also(requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)::getBoundsInScreen)
        fixture.labels.forEach { label ->
            val node = find(label)
            val r = Rect().also(node::getBoundsInScreen)
            assertTrue("Large label stays inside the visible bar: $label $r in $bar / $windowBounds", node.isVisibleToUser &&
                r.width() > 0 && r.height() > 0 && bar.contains(r) && windowBounds.contains(r))
        }
    }

    private inner class GlassTouch(private var x: Float, private val y: Float) : java.io.Closeable {
        private val downTime = SystemClock.uptimeMillis()
        private var active = true
        init { inject(MotionEvent.ACTION_DOWN) }
        private fun inject(action: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try { assertTrue("Touch injection failed: $action", instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        fun moveTo(targetX: Float) {
            check(active)
            val from = x
            // 连续 MOVE 越过触摸阈值，不能以无障碍 ACTION_CLICK 冒充拖动。
            for (step in 1..8) {
                SystemClock.sleep(25)
                x = from + (targetX - from) * step / 8f
                inject(MotionEvent.ACTION_MOVE)
            }
            instrumentation.waitForIdleSync(); SystemClock.sleep(200)
        }
        fun finish(cancel: Boolean = false) {
            check(active)
            inject(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP)
            active = false
            instrumentation.waitForIdleSync(); SystemClock.sleep(700)
        }
        override fun close() { if (active) finish(cancel = true) }
    }
    private fun glassEdge(fixture: GlassFixture, right: Boolean): Float {
        val bar = requireNotNull(fixture.bounds.get())
        val margin = 8 * context.resources.displayMetrics.density
        val x = if (right) (bar.right + margin).coerceAtMost(context.resources.displayMetrics.widthPixels - 1f)
            else (bar.left - margin).coerceAtLeast(1f)
        assertTrue("Edge probe must leave the bar but stay on screen", if (right) x > bar.right else x < bar.left)
        return x
    }
    private fun changedGlassPixels(before: Bitmap, after: Bitmap, bounds: Rect): Int {
        assertEquals(before.width, after.width); assertEquals(before.height, after.height)
        assertTrue("Pixel probe stays inside screenshot: $bounds", bounds.left >= 0 && bounds.top >= 0 &&
            bounds.right <= before.width && bounds.bottom <= before.height)
        var changed = 0
        for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
            val a = before.getPixel(x, y); val b = after.getPixel(x, y)
            // 忽略极小的混色舍入差异，仍检测真实高光、胶囊移动和跨 Tab 的反馈污染。
            if (kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) > 6 ||
                kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) > 6 ||
                kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)) > 6) changed++
        }
        return changed
    }

    @Test(timeout = 120_000) fun glassNavigationBlursPixelsAndKeepsTabsClickable() {
        org.junit.Assume.assumeTrue("Native lens and blur require API 33+", android.os.Build.VERSION.SDK_INT >= 33)
        val activity = launch(); cancelSourceTests()
        val fixture = GlassFixture().apply { radius.value = 0.dp }
        var sharp: Bitmap? = null; var blurred: Bitmap? = null
        try {
            mountGlassNavigation(activity, fixture)
            sharp = screenshot("glass-source-sharp.png")
            instrumentation.runOnMainSync { fixture.radius.value = 18.dp }
            SystemClock.sleep(700)
            blurred = screenshot("glass-source-blurred.png")
            val bar = requireNotNull(fixture.bounds.get())
            // 在中间两个未选 Tab 的间隙取多行像素，避开文字、图标、胶囊与镜片边缘。
            val sample = Rect(bar.left + (bar.width() * 0.46f).toInt(), bar.top + (bar.height() * 0.30f).toInt(),
                bar.left + (bar.width() * 0.54f).toInt(), bar.top + (bar.height() * 0.52f).toInt())
            fun stripeEnergy(image: Bitmap, region: Rect): Double {
                assertTrue("Stripe sample stays inside screenshot: $region", region.width() > 1 && region.height() > 0 &&
                    region.left >= 0 && region.top >= 0 && region.right <= image.width && region.bottom <= image.height)
                var energy = 0L; var count = 0
                for (y in region.top until region.bottom) for (x in region.left until region.right - 1) {
                    energy += kotlin.math.abs(android.graphics.Color.red(image.getPixel(x + 1, y)) -
                        android.graphics.Color.red(image.getPixel(x, y)))
                    count++
                }
                return energy.toDouble() / count
            }
            val sharpEnergy = stripeEnergy(sharp!!, sample)
            val blurredEnergy = stripeEnergy(blurred!!, sample)
            assertTrue("Fixture must contain visible sharp stripes: $sharpEnergy", sharpEnergy > 3)
            assertTrue("Backdrop blur must reduce stripe edges: $sharpEnergy -> $blurredEnergy", blurredEnergy < sharpEnergy * 0.55)
            val sourceSample = Rect(sample).apply { offset(0, -(bar.height() + 16 * context.resources.displayMetrics.density).toInt()) }
            val sourceEnergy = stripeEnergy(sharp!!, sourceSample)
            assertTrue("Native source stripes must remain sharp", sourceEnergy > 3)
            assertEquals("Changing bar blur must not blur the source Canvas", sourceEnergy, stripeEnergy(blurred!!, sourceSample), 0.1)
            assertTrue("Navigation label must remain painted above the blur",
                hasMultipleColors(blurred!!, Rect().also(find("工作台")::getBoundsInScreen)))
            click("浏览器"); assertGlassSelection(fixture, 3, listOf(3))
            SystemClock.sleep(700)
            val beforePress = screenshot("glass-navigation-before-press.png")
            var pressed: Bitmap? = null
            val target = glassTabBounds("浏览器")
            try {
                GlassTouch(target.exactCenterX(), target.exactCenterY()).use {
                    SystemClock.sleep(350)
                    pressed = screenshot("glass-navigation-pressed.png")
                    val nearby = Rect(target).apply { inset(-(8 * context.resources.displayMetrics.density).toInt(),
                        -(8 * context.resources.displayMetrics.density).toInt()); intersect(bar) }
                    val localChanges = changedGlassPixels(beforePress, pressed!!, nearby)
                    val outsideChanges = changedGlassPixels(beforePress, pressed!!, bar) - localChanges
                    // 允许选中胶囊放大和高光，但反馈不能蔓延到其他 Tab 或提前提交选择。
                    assertTrue("Selected capsule must provide press feedback", localChanges > 0)
                    assertTrue("Press feedback must stay near the selected capsule: $outsideChanges pixels",
                        outsideChanges <= (bar.width() * bar.height() - nearby.width() * nearby.height()) / 200)
                    fixture.labels.dropLast(1).forEach { label ->
                        val other = glassTabBounds(label).apply { inset((8 * context.resources.displayMetrics.density).toInt(), 0) }
                        assertTrue("Press must not change another tab: $label",
                            changedGlassPixels(beforePress, pressed!!, other) <= other.width() * other.height() / 200)
                    }
                    assertGlassSelection(fixture, 3, listOf(3))
                }
                assertGlassSelection(fixture, 3, listOf(3))
            } finally { beforePress.recycle(); pressed?.recycle() }
            instrumentation.runOnMainSync { fixture.selected.value = 4 }
            SystemClock.sleep(700)
            assertGlassSelection(fixture, 4, listOf(3))
            screenshot("glass-navigation-no-selection.png").recycle()
            instrumentation.runOnMainSync { fixture.useBackdrop.value = false }
            click("文件"); assertGlassSelection(fixture, 2, listOf(3, 2))
            screenshot("glass-navigation-fallback.png").recycle()
            instrumentation.runOnMainSync { fixture.rtl.value = true; fixture.fontScale.value = 2f }
            SystemClock.sleep(700)
            assertGlassLabelsVisible(fixture)
            click("DSH"); assertGlassSelection(fixture, 1, listOf(3, 2, 1))
            screenshot("glass-navigation-rtl-font200.png").recycle()
        } finally { sharp?.recycle(); blurred?.recycle(); cleanup(activity) }
    }

    @Test(timeout = 120_000) fun glassNavigationDragPreviewsThenCommitsOnceAndCancels() {
        val activity = launch(); cancelSourceTests()
        val fixture = GlassFixture()
        var before: Bitmap? = null; var during: Bitmap? = null; var committed: Bitmap? = null; var cancelled: Bitmap? = null
        try {
            mountGlassNavigation(activity, fixture)
            assertGlassSelection(fixture, 0, emptyList())
            val bar = requireNotNull(fixture.bounds.get())
            before = screenshot("glass-drag-before.png")
            // 从未选中的 Tab 开始，证明可拖区域不是只有当前胶囊。
            val start = glassTabBounds("DSH")
            GlassTouch(start.exactCenterX(), start.exactCenterY()).use { touch ->
                touch.moveTo(glassTabBounds("文件").exactCenterX())
                assertGlassSelection(fixture, 0, emptyList())
                touch.moveTo(glassEdge(fixture, right = true))
                during = screenshot("glass-drag-during.png")
                assertGlassSelection(fixture, 0, emptyList())
                val previewTarget = glassTabBounds("浏览器")
                assertTrue("Drag preview must reach the far tab without committing",
                    changedGlassPixels(before!!, during!!, previewTarget) > previewTarget.width() * previewTarget.height() / 100)
                touch.finish()
            }
            committed = screenshot("glass-drag-end.png")
            assertGlassSelection(fixture, 3, listOf(3))
            val last = glassTabBounds("浏览器")
            GlassTouch(last.exactCenterX(), last.exactCenterY()).use { touch ->
                touch.moveTo(glassEdge(fixture, right = false))
                assertGlassSelection(fixture, 3, listOf(3))
                screenshot("glass-drag-cancel-during.png").recycle()
                touch.finish(cancel = true)
            }
            cancelled = screenshot("glass-drag-cancel-end.png")
            assertGlassSelection(fixture, 3, listOf(3))
            assertTrue("Cancellation must restore the committed capsule", changedGlassPixels(committed!!, cancelled!!, bar) <= bar.width() * bar.height() / 100)
            GlassTouch(last.exactCenterX(), last.exactCenterY()).use { touch ->
                touch.moveTo(glassEdge(fixture, right = false))
                assertGlassSelection(fixture, 3, listOf(3))
                touch.finish()
            }
            assertGlassSelection(fixture, 0, listOf(3, 0))
            screenshot("glass-drag-left-clamped-end.png").recycle()
            val first = glassTabBounds("工作台")
            GlassTouch(first.exactCenterX(), first.exactCenterY()).use { touch ->
                touch.moveTo(glassTabBounds("文件").exactCenterX())
                assertGlassSelection(fixture, 0, listOf(3, 0))
                instrumentation.runOnMainSync { fixture.selected.value = 1 }
                SystemClock.sleep(700)
                touch.moveTo(glassEdge(fixture, right = true))
                touch.finish()
            }
            assertGlassSelection(fixture, 1, listOf(3, 0))
            screenshot("glass-drag-external-selection-end.png").recycle()
        } finally { before?.recycle(); during?.recycle(); committed?.recycle(); cancelled?.recycle(); cleanup(activity) }
    }

    @Test(timeout = 120_000) fun glassNavigationNullBackdropDragsInRtlAndKeepsLargeTabsClickable() {
        val activity = launch(); cancelSourceTests()
        val fixture = GlassFixture(backdrop = false, rtl = true, fontScale = 2f)
        var before: Bitmap? = null; var during: Bitmap? = null
        try {
            mountGlassNavigation(activity, fixture)
            assertGlassLabelsVisible(fixture)
            assertGlassSelection(fixture, 0, emptyList())
            val first = glassTabBounds("工作台"); val last = glassTabBounds("浏览器")
            assertTrue("RTL reverses physical tab order", first.centerX() > last.centerX())
            before = screenshot("glass-null-rtl-font200-before.png")
            GlassTouch(first.exactCenterX(), first.exactCenterY()).use { touch ->
                touch.moveTo(glassTabBounds("文件").exactCenterX())
                assertGlassSelection(fixture, 0, emptyList())
                touch.moveTo(glassEdge(fixture, right = false))
                during = screenshot("glass-null-rtl-font200-during.png")
                assertTrue("Null backdrop still previews the far tab",
                    changedGlassPixels(before!!, during!!, last) > last.width() * last.height() / 100)
                assertGlassSelection(fixture, 0, emptyList())
                touch.finish()
            }
            screenshot("glass-null-rtl-font200-end.png").recycle()
            assertGlassSelection(fixture, 3, listOf(3))
            GlassTouch(last.exactCenterX(), last.exactCenterY()).use { touch ->
                touch.moveTo(glassEdge(fixture, right = true))
                assertGlassSelection(fixture, 3, listOf(3))
                touch.finish()
            }
            assertGlassSelection(fixture, 0, listOf(3, 0))
            // 真实 DOWN/UP 验证拖动之后仍能点击，另保留无障碍点击路径。
            val file = glassTabBounds("文件")
            GlassTouch(file.exactCenterX(), file.exactCenterY()).use { touch ->
                SystemClock.sleep(60)
                touch.finish()
            }
            assertGlassSelection(fixture, 2, listOf(3, 0, 2))
            click("DSH"); assertGlassSelection(fixture, 1, listOf(3, 0, 2, 1))
            val dsh = glassTabBounds("DSH")
            GlassTouch(dsh.exactCenterX(), dsh.exactCenterY()).use { touch ->
                touch.moveTo(glassEdge(fixture, right = false))
                assertGlassSelection(fixture, 1, listOf(3, 0, 2, 1))
                // 布局方向重建后，旧手势的 UP 不能提交旧坐标映射得到的 Tab。
                instrumentation.runOnMainSync { fixture.rtl.value = false }
                SystemClock.sleep(700)
                touch.moveTo(glassEdge(fixture, right = true))
                touch.finish()
            }
            assertGlassSelection(fixture, 1, listOf(3, 0, 2, 1))
            assertGlassLabelsVisible(fixture)
            screenshot("glass-null-layout-change-end.png").recycle()
            val resizedStart = glassTabBounds("DSH")
            GlassTouch(resizedStart.exactCenterX(), resizedStart.exactCenterY()).use { touch ->
                touch.moveTo(glassTabBounds("文件").exactCenterX())
                instrumentation.runOnMainSync { fixture.narrow.value = true }
                SystemClock.sleep(700)
                touch.moveTo(glassEdge(fixture, right = true))
                touch.finish()
            }
            assertGlassSelection(fixture, 1, listOf(3, 0, 2, 1))
            assertGlassLabelsVisible(fixture)
            assertTrue("Tab supports keyboard focus", glassTab("文件").performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER)
            instrumentation.waitForIdleSync(); SystemClock.sleep(700)
            assertGlassSelection(fixture, 2, listOf(3, 0, 2, 1, 2))
            screenshot("glass-null-narrow-keyboard-end.png").recycle()
        } finally { before?.recycle(); during?.recycle(); cleanup(activity) }
    }

    @Test fun setupGuideFirstStepSourceVersionAndLocalThemeScreenshots() {
        val activity = launch(); cancelSourceTests()
        val state = mutableStateOf(model()); val rec = Recorder()
        val originalNight = isNight(activity.resources.configuration)
        var light: Bitmap? = null; var dark: Bitmap? = null
        try {
            val browser = browserOf(activity)
            mount(activity, state, rec, browser.view, night = false, fontScale = 1.3f)
            find("第 1 步，Ubuntu"); tap("第 2 步，Node.js"); find("先完成 Ubuntu")
            tap("第 3 步，基础工具"); find("先完成 Ubuntu")
            assertTrue("previewing guide steps must not install", rec.installs.isEmpty())
            assertTrue("previewing guide steps must not issue commands", rec.commands.isEmpty())
            tap("第 1 步，Ubuntu")
            click("查看测速与下载源"); click("使用清华 TUNA"); find("手动优先：清华 TUNA")
            assertTrue(rec.sourceSelections.contains(RuntimeComponent.UBUNTU to "tuna"))
            assertEquals("tuna", state.value.components.ubuntuSource)
            click("Ubuntu 镜像：Ubuntu Base 24.04.5 LTS"); click("Ubuntu Base 26.04.1 LTS")
            find("Ubuntu 镜像：Ubuntu Base 26.04.1 LTS")
            assertTrue(rec.versionSelections.contains(RuntimeComponent.UBUNTU to "26.04.1"))
            assertEquals("26.04.1", state.value.components.selectedUbuntuVersion)
            val beforeDownload=state.value.snapshot
            instrumentation.runOnMainSync {
                state.value=state.value.copy(snapshot=beforeDownload.copy(environment=EnvironmentState.DOWNLOADING,
                    stage="正在下载 Ubuntu",download=RuntimeDownloadProgress("ubuntu","测试源",2,10,1,false)))
            }
            tap("第 2 步，Node.js"); find("安装仍在继续")
            assertTrue("step navigation must not cancel the running install",rec.commands.isEmpty())
            tap("第 1 步，Ubuntu")
            instrumentation.runOnMainSync { state.value=state.value.copy(snapshot=beforeDownload) }
            light = run { SystemClock.sleep(300); screenshot("setup-guide-light-font130.png") }
            val nav = glassTabBounds("DSH")
            val density = context.resources.displayMetrics.density
            fun backgroundRed(y: Int) = (nav.centerX() - 8..nav.centerX() + 8).map { x ->
                android.graphics.Color.red(light!!.getPixel(x, y))
            }.average()
            // 此处上下都是合成页面留白，玻璃不能因顶部 inset 坐标错位而采到远处的深色安装按钮。
            val pageBackground = backgroundRed(nav.top - (24 * density).toInt())
            val glassBackground = backgroundRed(nav.top + (2 * density).toInt())
            assertTrue("Native backdrop must stay aligned with the page: $pageBackground -> $glassBackground",
                kotlin.math.abs(pageBackground - glassBackground) < 35)
            mount(activity, state, rec, browser.view, night = true, fontScale = 1.3f)
            find("手动优先：清华 TUNA"); find("Ubuntu 镜像：Ubuntu Base 26.04.1 LTS")
            dark = run { SystemClock.sleep(300); screenshot("setup-guide-dark-font130.png") }
            assertEquals("local night override must not mutate device/app configuration",
                originalNight, isNight(activity.resources.configuration))
            assertTrue("light and dark130% screenshots should differ", differ(light!!, dark!!))
            click("查看测速与下载源"); click("恢复自动择优")
            assertTrue("automatic selection can be restored",state.value.components.ubuntuAutomatic)
        } finally { light?.recycle(); dark?.recycle(); cleanup(activity) }
    }

    private fun hasDocumentPixels(image: Bitmap, view: WebView): Boolean {
        val r = boundsOf(view)
        val counts = HashMap<Int, Int>()
        // Exclude native toolbars and the floating exit button: they cannot prove web content painted.
        val top = (r.top + r.height() / 5).coerceIn(0, image.height - 1)
        val bottom = (r.bottom - r.height() / 5).coerceIn(top + 1, image.height)
        for (x in r.left.coerceAtLeast(0) until r.right.coerceAtMost(image.width) step 8)
            for (y in top until bottom step 8) {
                val color = image.getPixel(x, y)
                counts[color] = (counts[color] ?: 0) + 1
            }
        return counts.size >= 3 && counts.values.sum() - (counts.values.maxOrNull() ?: 0) > 80
    }

    @Test fun progressCoversSilentInstallStagesAndStaysVisibleAcrossPages() {
        val activity = launch(); cancelSourceTests()
        try {
            val browser = browserOf(activity); awaitProbe(browser)
            val now = SystemClock.elapsedRealtime()
            val state = mutableStateOf(model(Snapshot(environment=EnvironmentState.INSTALLING, update=UpdateState.STAGING,
                stage="解析 DSH 依赖", timing=OperationTiming(now-20_000,now))))
            val rec = Recorder(); mount(activity,state,rec,browser.view,false,1f)
            val stages = listOf(
                Snapshot(update=UpdateState.CHECKING,stage="获取官方 DSH 版本列表"),
                Snapshot(environment=EnvironmentState.CHECKING,stage="测速下载源"),
                Snapshot(environment=EnvironmentState.DOWNLOADING,stage="下载 Ubuntu",download=RuntimeDownloadProgress("ubuntu","测试源",100,-1,0)),
                Snapshot(environment=EnvironmentState.INSTALLING,stage="校验与解压 Node.js"),
                Snapshot(environment=EnvironmentState.INSTALLING,update=UpdateState.STAGING,stage="解析 DSH 依赖"),
                Snapshot(environment=EnvironmentState.INSTALLING,update=UpdateState.STAGING,stage="离线安装 DSH 依赖"),
                Snapshot(environment=EnvironmentState.VERIFYING,stage="检测必要组件",phasePercent=35),
                Snapshot(update=UpdateState.TESTING,stage="检查 DSH 候选启动"))
            for (stage in stages) {
                instrumentation.runOnMainSync { state.value=state.value.copy(snapshot=stage.copy(timing=OperationTiming(now-20_000,now))) }
                find(stage.stage)
                val bounds=Rect().also(find("操作进度")::getBoundsInScreen)
                assertTrue("progress must stay above the page's scroll region: ${stage.stage}", bounds.height()>0 && bounds.top<activity.resources.displayMetrics.heightPixels/2)
            }
            click("浏览器"); find("操作进度")
            click("文件"); find("操作进度")
            click("设置"); find("操作进度")
            click("查看进度"); click("取消当前操作")
            assertEquals(listOf("CANCEL"),rec.commands)
            click("收起")
            instrumentation.runOnMainSync { state.value=state.value.copy(snapshot=Snapshot(environment=EnvironmentState.READY)) }
            SystemClock.sleep(300)
            assertFalse("completed operation must remove banner",nodes().any { matches(it,"操作进度") })
        } finally { cleanup(activity) }
    }

    @Test fun progressShowsMeasuredBytesUnknownTransfersAndAdvancingElapsedTime() {
        val activity=launch();cancelSourceTests()
        try {
            val browser=browserOf(activity);awaitProbe(browser)
            val now=SystemClock.elapsedRealtime()
            val state=mutableStateOf(model(Snapshot(environment=EnvironmentState.DOWNLOADING,stage="下载 Node.js",
                download=RuntimeDownloadProgress("node","本地测试源",256,1024,64),timing=OperationTiming(now-5000,now))))
            mount(activity,state,Recorder(),browser.view,false,1f)
            assertEquals("当前阶段 25%",find("操作进度").stateDescription?.toString())
            val before=nodes().first { it.text?.startsWith("已用 ")==true }.text.toString()
            screenshot("progress-known-bytes.png").recycle()
            SystemClock.sleep(1400)
            val after=nodes().first { it.text?.startsWith("已用 ")==true }.text.toString()
            assertNotEquals("clock must keep responding while bytes are unchanged",before,after)
            instrumentation.runOnMainSync { state.value=state.value.copy(snapshot=Snapshot(environment=EnvironmentState.READY),
                transfers=listOf(FileTransferProgress(1,"下载网页文件",2048,-1,OperationTiming(now,now)),
                    FileTransferProgress(2,"准备网页 Blob 下载",0,-1,OperationTiming(now,now)))) }
            find("下载网页文件");assertEquals("进行中，无法估算百分比",find("操作进度").stateDescription?.toString())
            click("进度 · 2");find("准备网页 Blob 下载");find("已传输 2.0 KB · 总大小未知")
            screenshot("progress-unknown-transfers.png").recycle()
            click("收起")
            instrumentation.runOnMainSync { state.value=state.value.copy(transfers=emptyList()) }
            SystemClock.sleep(200);assertFalse(nodes().any { matches(it,"操作进度") })
        } finally { cleanup(activity) }
    }

    @Test fun quietProgressWarnsWithoutPretendingSuccessAtDarkLargeFont() {
        val activity=launch();cancelSourceTests()
        try {
            val browser=browserOf(activity);awaitProbe(browser)
            val now=SystemClock.elapsedRealtime()
            val state=mutableStateOf(model(Snapshot(environment=EnvironmentState.INSTALLING,update=UpdateState.STAGING,
                stage="解析 DSH 依赖",timing=OperationTiming(now-180_000,now-90_000))))
            mount(activity,state,Recorder(),browser.view,true,2f)
            find("操作进度");find("查看进度")
            assertTrue(nodes().any { it.text?.contains("没有新进展")==true })
            screenshot("progress-quiet-dark-font200.png").recycle()
            click("查看进度")
            assertTrue(nodes().any { it.text?.contains("计时和动效不代表后台一定正常")==true })
            click("收起")
        } finally { cleanup(activity) }
    }

    @Test fun installedOfficialDshPageSurvivesRealSystemBarFullscreen() {
        val runtime=(context.applicationContext as TanukiApp).runtime
        org.junit.Assume.assumeTrue("Requires an existing isolated DSH installation; never downloads or calls a model",runtime.components.state.value.dshInstalled)
        val startedByTest=runtime.state.value.service!=ServiceState.RUNNING
        val activity=launch();cancelSourceTests()
        try {
            if(startedByTest)runtime.start()
            val deadline=SystemClock.elapsedRealtime()+180_000
            while(runtime.state.value.service!=ServiceState.RUNNING && SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(200)
            assertEquals(ServiceState.RUNNING,runtime.state.value.service)
            click("DSH")
            val browser=browserOf(activity);val web=browser.view
            while(SystemClock.elapsedRealtime()<deadline && evalBoolean(web,"document.readyState==='complete' && location.protocol==='http:' && document.body.innerText.length>20")!=true)SystemClock.sleep(200)
            assertEquals(true,evalBoolean(web,"document.readyState==='complete' && location.protocol==='http:' && document.body.innerText.length>20"))
            eval(web,"window.__setupMarker='official-fullscreen-regression'")
            val before=awaitViewport(web)
            repeat(3) { cycle ->
                click("进入全屏");find("退出全屏")
                val full=awaitViewport(web) { it.h>before.h }
                assertEquals(before.timeOrigin,full.timeOrigin,0.0)
                assertEquals(before.marker,full.marker)
                SystemClock.sleep(500)
                assertEquals("fullscreen must not leave a black cutout strip above the window",0,boundsOf(activity.window.decorView).top)
                assertEquals("document must reach the top edge",0,boundsOf(web).top)
                val shot=screenshot("dsh-official-fullscreen-$cycle.png")
                try { assertTrue("official document pixels missing in fullscreen cycle $cycle",hasDocumentPixels(shot,web)) } finally { shot.recycle() }
                assertSame(web,attachedWebView(activity, web))
                if(cycle==1) { instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);SystemClock.sleep(300) }
                else click("退出全屏")
                find("进入全屏")
                assertEquals(before.timeOrigin,awaitViewport(web).timeOrigin,0.0)
            }
            screenshot("dsh-official-after-fullscreen.png").recycle()
        } finally {
            try { if(startedByTest) kotlinx.coroutines.runBlocking { runtime.stop() } }
            finally { cleanup(activity) }
        }
    }

    @Test fun actualDshWebViewSurvivesFullscreenWithoutReload() {
        val activity = launch(); cancelSourceTests()
        var beforeShot: Bitmap? = null; var fullShot: Bitmap? = null; var afterShot: Bitmap? = null
        try {
            val browser = browserOf(activity)
            awaitProbe(browser); val web = browser.view
            val state = mutableStateOf(model(snapshot = runningSnapshot())); val rec = Recorder()
            mount(activity, state, rec, web, night = isNight(activity.resources.configuration), fontScale = 1f)
            click("DSH"); awaitAttached(activity, web)
            loadHtml(web, VIEWPORT_HTML, "typeof window.__setupMarker==='string'")
            val before = awaitViewport(web); val marker = before.marker; val origin = before.timeOrigin
            // 在真实 DshWebView 附着时跨越多个页签拖动，预览和取消都不能拆卸或重载文档。
            val dragStart = glassTabBounds("DSH")
            GlassTouch(dragStart.exactCenterX(), dragStart.exactCenterY()).use { touch ->
                touch.moveTo(glassTabBounds("浏览器").exactCenterX())
                assertSame(web, attachedWebView(activity, web))
                assertEquals(origin, awaitViewport(web).timeOrigin, 0.0)
                touch.finish(cancel = true)
            }
            // 实际释放切到原生页再返回，也必须复用同一个网页实例与文档。
            GlassTouch(dragStart.exactCenterX(), dragStart.exactCenterY()).use { touch ->
                touch.moveTo(glassTabBounds("工作台").exactCenterX())
                touch.finish()
            }
            assertFalse(glassTab("DSH").isSelected)
            click("DSH"); awaitAttached(activity, web)
            assertSame(web, attachedWebView(activity, web))
            assertEquals(origin, awaitViewport(web).timeOrigin, 0.0)
            assertEquals(marker, awaitViewport(web).marker)
            find("进入全屏")
            val beforeBounds = boundsOf(web)
            beforeShot = run { SystemClock.sleep(300); screenshot("setup-webview-before.png") }
            assertTrue(before.w > 0 && before.h > 0 && boundsOf(web).width() > 0 && boundsOf(web).height() > 0)
            val navigation = glassTabBounds("文件")
            assertTrue("DSH viewport must end above the floating navigation", boundsOf(web).bottom <= navigation.top)
            assertTrue("DSH document must actually paint inside its safe viewport", hasDocumentPixels(beforeShot!!, web))
            click("进入全屏"); find("退出全屏")
            val full = awaitViewport(web) { it.h > before.h }
            fullShot = run { SystemClock.sleep(300); screenshot("setup-webview-fullscreen.png") }
            assertTrue("fullscreen callback was not exercised", rec.fullscreen.contains(true))
            assertSame("fullscreen must keep the same WebView instance", web, browser.view)
            assertSame("the attached browser must be the native DshWebView", web, attachedWebView(activity, web))
            assertEquals("page must not reload in fullscreen", marker, full.marker)
            assertEquals("timeOrigin must not change in fullscreen", origin, full.timeOrigin, 0.0)
            assertTrue("fullscreen viewport must be positive", full.w > 0 && full.h > 0)
            assertTrue("fullscreen must increase WebView height", full.h > before.h)
            assertTrue("fullscreen document pixels must paint outside native overlays", hasDocumentPixels(fullShot!!, web))
            assertEquals("no reserved cutout strip",0,boundsOf(activity.window.decorView).top)
            assertEquals("document reaches top edge",0,boundsOf(web).top)
            assertEquals(View.LAYER_TYPE_HARDWARE,web.layerType)
            click("退出全屏"); val after = awaitViewport(web) { it.marker == marker && it.h == before.h && boundsOf(web) == beforeBounds }
            afterShot = run { SystemClock.sleep(300); screenshot("setup-webview-after.png") }
            assertEquals(false, rec.fullscreen.last())
            assertEquals("release fullscreen texture on exit",View.LAYER_TYPE_NONE,web.layerType)
            assertEquals("DSH bounds must restore without an extra inset", beforeBounds, boundsOf(web))
            assertTrue("DSH must paint after leaving fullscreen", hasDocumentPixels(afterShot!!, web))
            assertEquals("page must not reload after exiting fullscreen", marker, after.marker)
            assertEquals("timeOrigin must not change after exiting fullscreen", origin, after.timeOrigin, 0.0)
        } finally { beforeShot?.recycle(); fullShot?.recycle(); afterShot?.recycle(); cleanup(activity) }
    }
    @Test fun fixedBottomDshControlsAcceptRealTouchesWithoutChangingNavigation() {
        val activity = launch(); cancelSourceTests()
        try {
            val browser = browserOf(activity); awaitProbe(browser)
            val web = browser.view
            val state = mutableStateOf(model(snapshot = runningSnapshot()))
            mount(activity, state, Recorder(), web, night = false, fontScale = 1f)
            click("DSH"); awaitAttached(activity, web)
            loadHtml(web, FixedBottomControlsFixture.HTML, "typeof window.__fixedBottomEvents==='object'")
            instrumentation.waitForIdleSync(); SystemClock.sleep(300)
            assertTrue("DSH navigation must be selected before the touch", glassTab("DSH").isSelected)
            screenshot("dsh-fixed-bottom-before.png").recycle()
            for ((id, event) in listOf("fixed-send" to "send", "fixed-more" to "more", "fixed-input" to "focus")) {
                val geometry = requireNotNull(evalObject(web, "(()=>{const r=document.getElementById('$id').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,w:innerWidth,h:innerHeight}})()"))
                var screen = Rect()
                instrumentation.runOnMainSync { screen = boundsOf(web) }
                val x = screen.left + geometry.getDouble("x").toFloat() * screen.width() / geometry.getDouble("w").toFloat()
                val y = screen.top + geometry.getDouble("y").toFloat() * screen.height() / geometry.getDouble("h").toFloat()
                // 只读坐标后触摸屏幕中心；只读输入框可验证焦点，避免软键盘改变本用例的导航布局。
                GlassTouch(x, y).use { touch -> SystemClock.sleep(60); touch.finish() }
                val count = evalObject(web, "window.__fixedBottomEvents")?.optInt(event, -1)
                val selected = glassTab("DSH").isSelected
                assertTrue("Fixed-bottom $id must receive its center touch ($x,$y), event=$count, dshSelected=$selected", count == 1 && selected)
            }
        } finally { cleanup(activity) }
    }

    @Test fun filesListExtendsBehindNavigationAndScrollsTailClearWithoutReloadingDsh() {
        val activity = launch(); cancelSourceTests()
        var overlapShot: Bitmap? = null; var tailShot: Bitmap? = null
        try {
            val browser = browserOf(activity); awaitProbe(browser)
            val web = browser.view
            val state = mutableStateOf(model(snapshot = runningSnapshot()))
            var openedTail = false
            // 只挂载合成列表，验证真实页面槽与尾部避让；不读取工作区或调用模型。
            mount(activity, state, Recorder(), web, night = false, fontScale = 1f) { modifier, bottomInset ->
                LazyColumn(modifier, contentPadding = PaddingValues(bottom = bottomInset)) {
                    items(40) { index ->
                        Text("合成文件 $index", Modifier.fillMaxWidth().height(64.dp).background(Color.Green)
                            .clickable { openedTail = index == 39 }.padding(16.dp), color = Color.Black)
                    }
                }
            }
            click("DSH"); awaitAttached(activity, web)
            loadHtml(web, VIEWPORT_HTML, "typeof window.__setupMarker==='string'")
            val before = awaitViewport(web)
            click("文件")
            val navigation = glassTabBounds("DSH")
            val listBounds = Rect().also(nodes().first { it.isScrollable }::getBoundsInScreen)
            assertTrue("Files content must extend behind the floating navigation", listBounds.contains(navigation))
            SystemClock.sleep(700)
            overlapShot = screenshot("files-floating-navigation-overlap.png")
            val pixel = overlapShot!!.getPixel(navigation.centerX(), navigation.top + (2 * context.resources.displayMetrics.density).toInt())
            assertTrue("Native list pixels must show through the navigation",
                android.graphics.Color.green(pixel) > android.graphics.Color.red(pixel) + 30 &&
                    android.graphics.Color.green(pixel) > android.graphics.Color.blue(pixel) + 30)
            assertFalse("The tail must start off screen", nodes().any { matches(it, "合成文件 39") && it.isVisibleToUser })
            find("合成文件 39")
            // 找到末项可能只露出一部分，再向前滚到尾部，让 contentPadding 完全进入视口。
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            instrumentation.waitForIdleSync(); SystemClock.sleep(500)
            val tail = Rect().also(find("合成文件 39")::getBoundsInScreen)
            assertTrue("The whole final row must remain above navigation: $tail / $navigation",
                tail.top >= listBounds.top && tail.bottom <= navigation.top &&
                    tail.height() >= (64 * context.resources.displayMetrics.density).toInt() - 1)
            tailShot = screenshot("files-floating-navigation-tail.png")
            assertTrue("The final row text must actually paint", hasMultipleColors(tailShot!!, tail))
            GlassTouch(tail.exactCenterX(), tail.exactCenterY()).use { touch ->
                SystemClock.sleep(60); touch.finish()
            }
            instrumentation.runOnMainSync { assertTrue("The exposed final row must accept a real tap", openedTail) }
            click("DSH"); awaitAttached(activity, web)
            val after = awaitViewport(web)
            assertSame(web, attachedWebView(activity, web))
            assertEquals("Files tab round trip must not reload DSH", before.timeOrigin, after.timeOrigin, 0.0)
            assertEquals(before.marker, after.marker)
        } finally { overlapShot?.recycle(); tailShot?.recycle(); cleanup(activity) }
    }

    @Test fun appThemeResourceDrivesWebViewColorSchemeWithoutGlobalChanges() {
        val activity = launch(); cancelSourceTests(); val themed = ArrayList<WebView>()
        try {
            val browser = browserOf(activity)
            awaitProbe(browser); val original = Configuration(activity.resources.configuration)
            for (dark in listOf(false, true)) {
                val view = createThemedWebView(dark); themed += view
                loadHtml(view, THEME_HTML, "window.__hpThemeReady==='hp-theme'")
                assertEquals("Theme.Tanuki must expose prefers-color-scheme=$dark",
                    dark, evalBoolean(view, "matchMedia('(prefers-color-scheme: dark)').matches"))
                assertEquals(if (dark) "rgb(0, 0, 0)" else "rgb(255, 255, 255)",
                    evalString(view, "getComputedStyle(document.body).backgroundColor"))
            }
            val expectedDark = isNight(original)
            loadHtml(browser.view, THEME_HTML, "window.__hpThemeReady==='hp-theme'")
            assertEquals("actual MainActivity WebView must match device uiMode",
                expectedDark, evalBoolean(browser.view, "matchMedia('(prefers-color-scheme: dark)').matches"))
            assertEquals(if (expectedDark) "rgb(0, 0, 0)" else "rgb(255, 255, 255)",
                evalString(browser.view, "getComputedStyle(document.body).backgroundColor"))
            assertEquals("theme checks must not mutate the activity configuration",
                expectedDark, isNight(activity.resources.configuration))
        } finally { runCatching { instrumentation.runOnMainSync { themed.forEach { it.destroy() } } }; cleanup(activity) }
    }
    private class Recorder {
        val commands = ArrayList<String>(); val installs = ArrayList<Release>()
        val sourceSelections = ArrayList<Pair<RuntimeComponent, String>>()
        val versionSelections = ArrayList<Pair<RuntimeComponent, String>>()
        val fullscreen = ArrayList<Boolean>()
    }
    companion object {
        private const val BASE_URL = "https://setup-presentation.tanuki.invalid/"
        private val UBUNTU_SOURCES = listOf(
            RuntimeSource("official", "Ubuntu 官方", "https://cdimage.ubuntu.com/ubuntu-base/", "cdimage.ubuntu.com"),
            RuntimeSource("tuna", "清华 TUNA", "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/", "mirrors.tuna.tsinghua.edu.cn"))
        private val NODE_SOURCES = listOf(
            RuntimeSource("official", "Node.js 官方", "https://nodejs.org/dist/", "nodejs.org"),
            RuntimeSource("tuna", "清华 TUNA", "https://mirrors.tuna.tsinghua.edu.cn/nodejs-release/", "mirrors.tuna.tsinghua.edu.cn"))
        private val VIEWPORT_HTML = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;width:100%;height:100%;overflow:hidden}body{background:linear-gradient(90deg,#ff0000 0 33%,#00ff00 33% 66%,#0000ff 66% 100%)}#marker{position:absolute;left:8px;top:8px;width:40%;height:40%;background:#ffff00}</style></head><body><div id="marker"></div><script>window.__setupMarker='TANUKI_'+Math.random().toString(36).slice(2)+'_'+Date.now();</script></body></html>"""
        private val THEME_HTML = """<!doctype html><html><head><meta charset="utf-8"><style>html,body{margin:0;width:100%;height:100%}body{background-color:rgb(255,255,255)}@media (prefers-color-scheme: dark){body{background-color:rgb(0,0,0)}}</style></head><body><script>window.__hpThemeReady='hp-theme';</script></body></html>"""
    }
}
