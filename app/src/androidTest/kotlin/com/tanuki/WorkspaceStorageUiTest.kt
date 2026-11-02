package com.tanuki

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.storage.WorkspaceCapacity
import com.tanuki.ui.TanukiTheme
import com.tanuki.ui.WorkspaceStorageControls
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** These UI tests require the separate workspacecheck package, never the user's main app. */
class WorkspaceStorageUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val runtime get() = (context.applicationContext as TanukiApp).runtime

    private fun launch(): MainActivity {
        check(context.packageName.endsWith(".workspacecheck")) { "Use scripts/workspace-verification.init.gradle for UI test isolation" }
        return instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        if (root.packageName?.toString() != context.packageName) return emptyList()
        val all = ArrayList<AccessibilityNodeInfo>()
        val pending = java.util.ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty() && all.size < 1000) {
            val node = pending.removeFirst()
            // Dynamic Compose children can be absent from UiAutomation's cached tree after expansion.
            if (!node.refresh()) continue
            all.add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
        }
        return all
    }
    private fun matches(node: AccessibilityNodeInfo, text: String): Boolean = node.text?.toString() == text ||
        node.contentDescription?.toString() == text || node.hintText?.toString() == text
    private fun find(text: String, backwards: Boolean = false): AccessibilityNodeInfo {
        repeat(40) { attempt ->
            val current = nodes()
            current.firstOrNull { matches(it, text) && it.isVisibleToUser }?.let { return it }
            val hidden = current.firstOrNull { matches(it, text) }
            if (hidden != null) hidden.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            else {
                val reverse = backwards.xor((attempt / 8) % 2 != 0)
                val action = if (reverse) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                current.firstOrNull { it.isScrollable && it.actionList.any { candidate -> candidate.id == action } }?.performAction(action)
            }
            android.os.SystemClock.sleep(120)
        }
        throw AssertionError("Visible control not found: $text")
    }
    private fun click(text: String, backwards: Boolean = false) {
        var node: AccessibilityNodeInfo? = find(text, backwards)
        while (node != null && !node.isClickable) node = node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Control did not accept click: $text" }
        android.os.SystemClock.sleep(150)
    }
    private fun setCapacity(text: String) {
        var input: AccessibilityNodeInfo? = null
        repeat(16) { attempt ->
            if (input == null) {
                input = nodes().firstOrNull { it.isEditable && it.isVisibleToUser }
                if (input == null) {
                    check(nodes().isNotEmpty()) { "isolated settings window lost focus" }
                    val metrics = context.resources.displayMetrics
                    val x = metrics.widthPixels / 2f
                    val reverse = (attempt / 4) % 2 != 0
                    val from = metrics.heightPixels * if (reverse) .38f else .72f
                    val to = metrics.heightPixels * if (reverse) .72f else .38f
                    val down = android.os.SystemClock.uptimeMillis()
                    for (step in 0..10) {
                        val action = when (step) {
                            0 -> android.view.MotionEvent.ACTION_DOWN
                            10 -> android.view.MotionEvent.ACTION_UP
                            else -> android.view.MotionEvent.ACTION_MOVE
                        }
                        val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action,
                            x, from + (to - from) * step / 10, 0)
                        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                        try { check(instrumentation.uiAutomation.injectInputEvent(event, true)) }
                        finally { event.recycle() }
                        android.os.SystemClock.sleep(20)
                    }
                    android.os.SystemClock.sleep(250)
                }
            }
        }
        if (input == null) {
            screenshot("workspace-budget-input-failure.png")
            File(context.cacheDir, "workspace-budget-input-failure-tree.txt").writeText(nodes().joinToString("\n") { node ->
                val bounds = Rect().also(node::getBoundsInScreen)
                "text=${node.text} class=${node.className} editable=${node.isEditable} visible=${node.isVisibleToUser} " +
                    "enabled=${node.isEnabled} scrollable=${node.isScrollable} actions=${node.actionList.map { it.id }} bounds=$bounds"
            })
        }
        val field = requireNotNull(input) { "capacity input unavailable" }
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
        android.os.SystemClock.sleep(150)
    }
    private fun screenshot(name: String) {
        val image = instrumentation.uiAutomation.takeScreenshot() ?: error("screenshot unavailable")
        File(context.cacheDir, name).outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
    }

    @Test fun settingsBudgetSavesThroughTheActualActivity(): Unit = runBlocking {
        val activity = launch()
        val previous = runtime.workspaceStorage.state.value.limitBytes
        try {
            click("设置")
            click("存储与空间")
            click("调整容量预算")
            click("自定容量上限")
            screenshot("workspace-budget-custom.png")
            File(context.cacheDir, "workspace-budget-custom-tree.txt").writeText(nodes().joinToString("\n") { node ->
                val bounds = Rect().also(node::getBoundsInScreen)
                "text=${node.text} hint=${node.hintText} description=${node.contentDescription} class=${node.className} " +
                    "editable=${node.isEditable} visible=${node.isVisibleToUser} enabled=${node.isEnabled} " +
                    "selected=${node.isSelected} checked=${node.isChecked} clickable=${node.isClickable} bounds=$bounds"
            })
            setCapacity("0.5")
            click("保存预算")
            withTimeout(10000) { while (runtime.workspaceStorage.state.value.limitBytes != WorkspaceCapacity.GIB / 2) delay(50) }
            assertNull(runtime.workspaceStorage.state.value.configurationError)
            find("项目空间", backwards = true)
            screenshot("workspace-settings-light.png")
            // Check persisted state independently of the active composition.
            val persisted = com.tanuki.storage.WorkspaceStorage(com.tanuki.runtime.Paths(context))
            assertEquals(WorkspaceCapacity.GIB / 2, persisted.state.value.limitBytes)
        } finally {
            withContext(Dispatchers.IO) { runtime.configureWorkspaceCapacity(previous) }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun darkLargeFontEditorShowsValidationWithoutChangingTheBudget(): Unit = runBlocking {
        val activity = launch()
        val previous = runtime.workspaceStorage.state.value.limitBytes
        try {
            withContext(Dispatchers.IO) { runtime.configureWorkspaceCapacity(WorkspaceCapacity.GIB / 2) }
            instrumentation.runOnMainSync {
                val configuration = Configuration(activity.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
                }
                val density = activity.resources.displayMetrics.density
                activity.setContent {
                    CompositionLocalProvider(LocalConfiguration provides configuration, LocalDensity provides Density(density, 1.3f)) {
                        TanukiTheme {
                            val state by runtime.workspaceStorage.state.collectAsState()
                            Surface(Modifier.fillMaxSize()) {
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                                    WorkspaceStorageControls(state, canEdit = true,
                                        onRefresh = runtime::refreshWorkspaceStorage,
                                        onSave = { limit -> withContext(Dispatchers.IO) { runtime.configureWorkspaceCapacity(limit) } })
                                }
                            }
                        }
                    }
                }
            }
            click("调整容量预算")
            setCapacity("0.0001")
            click("保存预算")
            find("容量预算至少为 64 MiB（0.0625 GiB）")
            assertEquals(WorkspaceCapacity.GIB / 2, runtime.workspaceStorage.state.value.limitBytes)
            var save = find("保存预算")
            while (!save.isClickable && save.parent != null) save = save.parent
            assertTrue("Save must expose its button target", save.isClickable)
            save.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            android.os.SystemClock.sleep(150)
            val bounds = Rect().also(save::getBoundsInScreen)
            assertTrue(bounds.width() > 0)
            assertTrue("Save target must be at least 48dp: $bounds", bounds.height() >= 48 * context.resources.displayMetrics.density)
            assertTrue(bounds.left >= 0 && bounds.right <= context.resources.displayMetrics.widthPixels)
            screenshot("workspace-editor-dark-font130.png")
        } finally {
            withContext(Dispatchers.IO) { runtime.configureWorkspaceCapacity(previous) }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
