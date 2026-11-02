package com.tanuki

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** 只检查应用标识与显示名称，不启动 Activity 或访问应用数据。 */
@RunWith(AndroidJUnit4::class)
class AppBrandingTest {
    @Test fun applicationAndLauncherAreNamedTanuki() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packages = context.packageManager
        assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
        assertEquals("com.tanuki.TanukiApp", context.applicationInfo.className)
        assertEquals("Tanuki", context.getString(R.string.app_name))
        assertEquals("Tanuki", context.applicationInfo.loadLabel(packages).toString())
        val launcher = packages.getLaunchIntentForPackage(context.packageName)
        assertNotNull("Application must have a launcher", launcher)
        val activity = packages.resolveActivity(launcher!!, 0)?.activityInfo
        assertNotNull("Launcher must resolve", activity)
        assertEquals("Tanuki", activity!!.loadLabel(packages).toString())
    }
}
