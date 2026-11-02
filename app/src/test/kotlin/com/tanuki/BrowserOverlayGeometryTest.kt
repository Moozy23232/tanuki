package com.tanuki

import com.tanuki.browser.*
import org.junit.Assert.*
import org.junit.Test

class BrowserOverlayGeometryTest {
    private val screen = BrowserOverlaySpace(10, 24, 400, 700)
    private val start = BrowserOverlayFrame(50, 80, 280, 400)
    @Test fun fitKeepsWindowInsideSafeAreaAndHonorsMinimums() {
        assertEquals(BrowserOverlayFrame(10, 24, 280, 300), BrowserOverlayGeometry.fit(BrowserOverlayFrame(-200, -200, 1, 1), screen, 280, 300))
        assertEquals(BrowserOverlayFrame(10, 24, 400, 700), BrowserOverlayGeometry.fit(BrowserOverlayFrame(999, 999, 9999, 9999), screen, 280, 300))
    }
    @Test fun moveClampsBothDirectionsWithoutResizing() {
        assertEquals(BrowserOverlayFrame(10, 24, 280, 400), BrowserOverlayGeometry.move(start, -9999, -9999, screen, 280, 300))
        assertEquals(BrowserOverlayFrame(130, 324, 280, 400), BrowserOverlayGeometry.move(start, 9999, 9999, screen, 280, 300))
    }
    @Test fun resizeChangesBothDimensionsWithoutMovingTheTopLeft() {
        assertEquals(BrowserOverlayFrame(50, 80, 320, 450), BrowserOverlayGeometry.resize(start, 40, 50, screen, 280, 300))
        assertEquals(BrowserOverlayFrame(50, 80, 280, 300), BrowserOverlayGeometry.resize(start, -999, -999, screen, 280, 300))
        assertEquals(BrowserOverlayFrame(50, 80, 360, 644), BrowserOverlayGeometry.resize(start, 999, 999, screen, 280, 300))
    }
    @Test fun lowerLeftResizeKeepsRightEdgeAndBothDirectionsAreBounded() {
        assertEquals(BrowserOverlayFrame(90, 80, 240, 450), BrowserOverlayGeometry.resize(start, 40, 50, screen, 200, 300, left = true))
        assertEquals(BrowserOverlayFrame(10, 80, 320, 450), BrowserOverlayGeometry.resize(start, -999, 50, screen, 280, 300, left = true))
        assertEquals(BrowserOverlayFrame(130, 80, 200, 300), BrowserOverlayGeometry.resize(start, 999, -999, screen, 200, 300, left = true))
        assertEquals(330, BrowserOverlayGeometry.resize(start, Int.MAX_VALUE, 0, screen, 200, 300, left = true).let { it.x + it.width })
        assertEquals(10, BrowserOverlayGeometry.resize(start, Int.MIN_VALUE, 0, screen, 200, 300, left = true).x)
    }
    @Test fun rotationAndSmallDisplaysAlwaysKeepTheWindowReachable() {
        val narrow = BrowserOverlaySpace(0, 20, 200, 250)
        assertEquals(BrowserOverlayFrame(0, 20, 200, 250), BrowserOverlayGeometry.fit(start, narrow, 280, 300))
        val rotated = BrowserOverlaySpace(20, 0, 700, 350)
        val fitted = BrowserOverlayGeometry.fit(start, rotated, 280, 300)
        assertEquals(0, fitted.y); assertEquals(350, fitted.height)
        assertTrue(fitted.x >= rotated.left && fitted.x + fitted.width <= rotated.left + rotated.width)
    }
    @Test fun collapsedHeaderCanMoveToBottomThenExpansionReclamps() {
        val compact = start.copy(height = 56)
        val bottom = BrowserOverlayGeometry.move(compact, 0, 999, screen, 280, 56)
        assertEquals(668, bottom.y)
        val expanded = BrowserOverlayGeometry.fit(bottom.copy(height = 400), screen, 280, 300)
        assertEquals(324, expanded.y)
    }
    @Test fun ExtremeGestureDeltasCannotOverflowToOppositeEdge() {
        assertEquals(130, BrowserOverlayGeometry.move(start, Int.MAX_VALUE, 0, screen, 280, 300).x)
        assertEquals(10, BrowserOverlayGeometry.move(start, Int.MIN_VALUE, 0, screen, 280, 300).x)
        assertEquals(360, BrowserOverlayGeometry.resize(start, Int.MAX_VALUE, 0, screen, 280, 300).width)
    }
}
