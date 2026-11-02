package com.tanuki

import android.accessibilityservice.AccessibilityServiceInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** UiAutomation includes intentionally hidden/ignored nodes by default, unlike user navigation. */
class VisibleAccessibilityRule : ExternalResource() {
    private var previous: Int? = null
    override fun before() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val info = automation.serviceInfo
        previous = info.flags
        // Keep mounted webpage semantics, but respect the parked WebViews' NO_HIDE_DESCENDANTS.
        info.flags = info.flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS.inv()
        automation.serviceInfo = info
    }
    override fun after() {
        previous?.let { flags ->
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
        }
    }
}
