package com.tanuki.ui

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// Small, consistent native line-icon set; avoids bundling thousands of unused icon classes.
private fun icon(name: String, draw: PathBuilder.() -> Unit) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
}.build()
private fun PathBuilder.circle(x: Float, y: Float, r: Float) {
    moveTo(x + r, y); arcTo(r, r, 0f, true, true, x - r, y); arcTo(r, r, 0f, true, true, x + r, y); close()
}
private fun PathBuilder.box(x: Float, y: Float, w: Float, h: Float) { moveTo(x, y); lineTo(x + w, y); lineTo(x + w, y + h); lineTo(x, y + h); close() }
private object BrowserSymbols {
    val globe = icon("BrowserGlobe") { circle(12f, 12f, 9f); moveTo(3f,12f); lineTo(21f,12f); moveTo(12f,3f); curveTo(5f,7f,5f,17f,12f,21f); curveTo(19f,17f,19f,7f,12f,3f) }
    val robot = icon("BrowserAgent") { box(4f,7f,16f,13f); moveTo(12f,3f); lineTo(12f,7f); moveTo(8f,11f); lineTo(8f,13f); moveTo(16f,11f); lineTo(16f,13f); moveTo(9f,17f); lineTo(15f,17f) }
    val tabs = icon("BrowserTabs") { box(7f,7f,14f,14f); moveTo(3f,16f); lineTo(3f,3f); lineTo(16f,3f) }
    val trash = icon("BrowserDelete") { moveTo(3f,6f); lineTo(21f,6f); moveTo(8f,6f); lineTo(8f,3f); lineTo(16f,3f); lineTo(16f,6f); moveTo(5f,6f); lineTo(6f,21f); lineTo(18f,21f); lineTo(19f,6f); moveTo(10f,10f); lineTo(10f,17f); moveTo(14f,10f); lineTo(14f,17f) }
    val tune = icon("BrowserPreferences") { moveTo(3f,6f); lineTo(7f,6f); moveTo(11f,6f); lineTo(21f,6f); circle(9f,6f,2f); moveTo(3f,18f); lineTo(13f,18f); moveTo(17f,18f); lineTo(21f,18f); circle(15f,18f,2f) }
    val shield = icon("BrowserPermissions") { moveTo(12f,3f); lineTo(20f,6f); lineTo(20f,12f); curveTo(20f,16f,16f,20f,12f,22f); curveTo(8f,20f,4f,16f,4f,12f); lineTo(4f,6f); close(); moveTo(8f,12f); lineTo(11f,15f); lineTo(16f,9f) }
    val folder = icon("BrowserFolder") { moveTo(3f,5f); lineTo(10f,5f); lineTo(13f,8f); lineTo(21f,8f); lineTo(21f,20f); lineTo(3f,20f); close() }
    val devices = icon("BrowserDisplayMode") { box(2f,4f,14f,11f); moveTo(9f,15f); lineTo(9f,20f); moveTo(5f,20f); lineTo(13f,20f); box(17f,9f,5f,12f) }
    val history = icon("BrowserHistory") { circle(12f,12f,9f); moveTo(12f,6f); lineTo(12f,12f); lineTo(16f,15f) }
    val bookmark = icon("BrowserBookmark") { moveTo(6f,3f); lineTo(18f,3f); lineTo(18f,21f); lineTo(12f,17f); lineTo(6f,21f); close() }
    val download = icon("BrowserDownload") { moveTo(12f,3f); lineTo(12f,16f); moveTo(7f,11f); lineTo(12f,16f); lineTo(17f,11f); moveTo(4f,17f); lineTo(4f,21f); lineTo(20f,21f); lineTo(20f,17f) }
    val minus = icon("BrowserZoomOut") { moveTo(5f,12f); lineTo(19f,12f) }
    val zoom = icon("BrowserZoomIn") { circle(10f,10f,7f); moveTo(15f,15f); lineTo(21f,21f); moveTo(10f,6f); lineTo(10f,14f); moveTo(6f,10f); lineTo(14f,10f) }
    val screenshot = icon("BrowserCapture") { moveTo(3f,8f); lineTo(3f,3f); lineTo(8f,3f); moveTo(16f,3f); lineTo(21f,3f); lineTo(21f,8f); moveTo(21f,16f); lineTo(21f,21f); lineTo(16f,21f); moveTo(8f,21f); lineTo(3f,21f); lineTo(3f,16f); box(7f,7f,10f,10f) }
    val tools = icon("BrowserTools") { box(3f,8f,18f,12f); moveTo(8f,8f); lineTo(8f,4f); lineTo(16f,4f); lineTo(16f,8f); moveTo(3f,13f); lineTo(21f,13f); moveTo(10f,12f); lineTo(10f,15f); lineTo(14f,15f); lineTo(14f,12f) }
    val chevron = icon("BrowserNext") { moveTo(9f,5f); lineTo(16f,12f); lineTo(9f,19f) }
}
internal val Icons.Filled.Public get() = BrowserSymbols.globe
internal val Icons.Filled.SmartToy get() = BrowserSymbols.robot
internal val Icons.Filled.Tab get() = BrowserSymbols.tabs
internal val Icons.Filled.DeleteOutline get() = BrowserSymbols.trash
internal val Icons.Filled.Tune get() = BrowserSymbols.tune
internal val Icons.Filled.Security get() = BrowserSymbols.shield
internal val Icons.Filled.FolderOpen get() = BrowserSymbols.folder
internal val Icons.Filled.Devices get() = BrowserSymbols.devices
internal val Icons.Filled.History get() = BrowserSymbols.history
internal val Icons.Filled.Bookmarks get() = BrowserSymbols.bookmark
internal val Icons.Filled.BookmarkAdd get() = BrowserSymbols.bookmark
internal val Icons.Filled.Download get() = BrowserSymbols.download
internal val Icons.Filled.Remove get() = BrowserSymbols.minus
internal val Icons.Filled.ZoomIn get() = BrowserSymbols.zoom
internal val Icons.Filled.Screenshot get() = BrowserSymbols.screenshot
internal val Icons.Filled.Construction get() = BrowserSymbols.tools
internal val Icons.Filled.ChevronRight get() = BrowserSymbols.chevron
