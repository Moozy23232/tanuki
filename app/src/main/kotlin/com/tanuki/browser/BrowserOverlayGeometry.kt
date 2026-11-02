package com.tanuki.browser

/** Pixel coordinates of the actual, touchable overlay, never a full-screen transparent mask. */
data class BrowserOverlayFrame(val x: Int, val y: Int, val width: Int, val height: Int)
internal data class BrowserOverlaySpace(val left: Int, val top: Int, val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) }
}
internal object BrowserOverlayGeometry {
    fun fit(frame: BrowserOverlayFrame, area: BrowserOverlaySpace, minimumWidth: Int, minimumHeight: Int): BrowserOverlayFrame {
        val width = frame.width.coerceIn(minimumWidth.coerceIn(1, area.width), area.width)
        val height = frame.height.coerceIn(minimumHeight.coerceIn(1, area.height), area.height)
        return BrowserOverlayFrame(frame.x.coerceIn(area.left, area.left + area.width - width),
            frame.y.coerceIn(area.top, area.top + area.height - height), width, height)
    }
    fun move(frame: BrowserOverlayFrame, dx: Int, dy: Int, area: BrowserOverlaySpace, minWidth: Int, minHeight: Int) =
        fit(frame.copy(x = add(frame.x, dx), y = add(frame.y, dy)), area, minWidth, minHeight)
    fun resize(frame: BrowserOverlayFrame, dx: Int, dy: Int, area: BrowserOverlaySpace, minWidth: Int, minHeight: Int, left: Boolean = false): BrowserOverlayFrame {
        val base = fit(frame, area, minWidth, minHeight)
        val effectiveMinimumWidth = minWidth.coerceIn(1, base.width)
        val width: Int
        val x: Int
        if (left) {
            // The right edge stays fixed while the lower-left grip changes both x and width.
            val right = base.x + base.width
            x = add(base.x, dx).coerceIn(area.left, right - effectiveMinimumWidth)
            width = right - x
        } else {
            x = base.x
            width = add(base.width, dx).coerceIn(effectiveMinimumWidth, area.left + area.width - base.x)
        }
        return BrowserOverlayFrame(x, base.y, width,
            add(base.height, dy).coerceIn(minHeight.coerceIn(1, base.height), area.top + area.height - base.y))
    }
    private fun add(value: Int, delta: Int) = (value.toLong() + delta).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
}
