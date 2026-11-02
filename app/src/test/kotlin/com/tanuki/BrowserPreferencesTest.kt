package com.tanuki

import com.tanuki.browser.*
import org.junit.Assert.*
import org.junit.Test

class BrowserPreferencesTest {
    @Test fun attachmentNamesPreferUtf8AndPreserveServerExtension() {
        assertEquals("browser-fixture.txt", com.tanuki.browser.attachmentName("attachment; filename=browser-fixture.txt"))
        assertEquals("a b.txt", com.tanuki.browser.attachmentName("attachment; filename=\"a b.txt\""))
        assertEquals("中文.txt", com.tanuki.browser.attachmentName("attachment; filename=fallback.txt; filename*=UTF-8''%E4%B8%AD%E6%96%87.txt"))
        assertNull(com.tanuki.browser.attachmentName(null))
    }
    @Test fun searchesEncodeQueryAndSwitchProviders() {
        val query = "a&b 中文"
        for (engine in BrowserSearchEngine.entries) {
            assertEquals(engine.search(query), browserAddress(query, engine))
            assertTrue(engine.search(query).contains("a%26b+%E4%B8%AD%E6%96%87"))
        }
        assertNotEquals(BrowserSearchEngine.BING.search("test"), BrowserSearchEngine.BAIDU.search("test"))
    }
    @Test fun addressesDoNotTurnDangerousSchemesIntoSearches() {
        val engine = BrowserSearchEngine.BING
        assertEquals("https://example.com/path", browserAddress(" example.com/path ", engine))
        assertEquals("http://127.0.0.1:8080/a", browserAddress("http://127.0.0.1:8080/a", engine))
        assertEquals("https://localhost:8080/a", browserAddress("localhost:8080/a", engine))
        for (value in listOf("javascript:alert(1)", "file:///etc/passwd", "data:text/html,test")) assertEquals(value, browserAddress(value, engine))
        assertEquals(engine.search("browser test"), browserAddress("browser test", engine))
    }
    @Test fun desktopModesPreserveEngineVersionButNotMobileIdentity() {
        val mobile = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/159.0.1.2 Mobile Safari/537.36"
        assertEquals(mobile, BrowserPageMode.MOBILE.userAgent(mobile))
        for (mode in BrowserPageMode.entries.filter { it != BrowserPageMode.MOBILE }) {
            assertFalse(mode.userAgent(mobile).contains("Android")); assertFalse(mode.userAgent(mobile).contains("Mobile"))
            assertTrue(mode.userAgent(mobile).contains("Chrome/159.0.1.2"))
        }
        assertTrue(BrowserPageMode.MAC.userAgent(mobile).contains("Macintosh"))
        assertTrue(BrowserPageMode.WINDOWS.userAgent(mobile).contains("Windows NT"))
    }
    @Test fun newPagesDefaultToWindowsWithoutChangingExplicitMobilePreference() {
        assertEquals("WINDOWS", SavedBrowserTab("id", "", "").mode)
        assertEquals(BrowserPageMode.WINDOWS, BrowserPageMode.from(""))
        assertEquals(BrowserPageMode.WINDOWS, BrowserPageMode.from("invalid"))
        assertEquals(BrowserPageMode.MOBILE, BrowserPageMode.from("MOBILE"))
        assertEquals(BrowserSearchEngine.BING, BrowserSearchEngine.from("invalid"))
    }
}
