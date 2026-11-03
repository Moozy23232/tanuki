package com.tanuki

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.ui.ApplicationUpdateCard
import com.tanuki.ui.TanukiTheme
import com.tanuki.update.AppUpdatePhase
import com.tanuki.update.AppUpdateState
import org.junit.Assert.*
import org.junit.Test

/** 只使用合成状态/回调，不联网、不启动安装器、不改运行环境。 */
class ApplicationUpdatePresentationTest {
    @Test fun checkActionIsReachableAt320dpWithLargeFontsInBothThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".workspacecheck"))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            for (dark in listOf(false, true)) {
                val checked = AtomicBoolean()
                val fixture = "应用更新测试-${if (dark) "dark" else "light"}-320dp-150"
                instrumentation.runOnMainSync {
                    activity.setContent {
                        val configuration = Configuration(LocalConfiguration.current).apply {
                            screenWidthDp = 320
                            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                        }
                        CompositionLocalProvider(LocalConfiguration provides configuration, LocalDensity provides Density(1f, 1.5f)) {
                            TanukiTheme {
                                Box(Modifier.fillMaxSize()) {
                                    Surface(Modifier.width(320.dp).verticalScroll(rememberScrollState()).semantics { contentDescription = fixture }) {
                                        ApplicationUpdateCard(AppUpdateState(AppUpdatePhase.NO_RELEASE, "暂无可用于应用内更新的 Release"),
                                            "0.1.2", false, { checked.set(true) }, {}, {}, {})
                                    }
                                }
                            }
                        }
                    }
                }
                instrumentation.waitForIdleSync()
                val deadline = SystemClock.elapsedRealtime() + 8_000
                var button: AccessibilityNodeInfo? = null
                while (button == null && SystemClock.elapsedRealtime() < deadline) {
                    // Compose 虚拟节点不能依赖平台的 findByText 实现，逐层读取真实无障碍树。
                    val pending = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
                    instrumentation.uiAutomation.rootInActiveWindow?.takeIf { it.packageName?.toString() == context.packageName }?.let { pending.add(it to false) }
                    var visited = 0
                    while (pending.isNotEmpty() && visited++ < 1000) {
                        val (node, inside) = pending.removeFirst()
                        val inFixture = inside || node.contentDescription?.toString()?.contains(fixture) == true
                        if (inFixture && node.isVisibleToUser && (node.text?.toString() == "检查更新" || node.contentDescription?.toString() == "检查更新")) {
                            button = node; break
                        }
                        for (i in 0 until node.childCount) node.getChild(i)?.let { pending.add(it to inFixture) }
                    }
                    if (button == null) SystemClock.sleep(100)
                }
                assertNotNull("Update action missing", button)
                var clickable = button
                while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                assertNotNull(clickable)
                val bounds = Rect().also { clickable!!.getBoundsInScreen(it) }
                assertTrue("操作必须实际位于 320dp 宽度内", bounds.width() in 200..320)
                assertTrue(clickable!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                val clickDeadline = SystemClock.elapsedRealtime() + 2_000
                while (!checked.get() && SystemClock.elapsedRealtime() < clickDeadline) SystemClock.sleep(25)
                assertTrue("$fixture 的检查回调未触发", checked.get())
                instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                    File(context.cacheDir, "app-update-${if (dark) "dark" else "light"}-320.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    image.recycle()
                }
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
