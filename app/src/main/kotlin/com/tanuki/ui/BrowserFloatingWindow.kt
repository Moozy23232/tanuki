package com.tanuki.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/** In-app window only. No SYSTEM_ALERT_WINDOW permission, no overlay above other applications. */
@Composable
internal fun BrowserFloatingWindow(modifier: Modifier, onClose: () -> Unit, onExpand: () -> Unit,
    content: @Composable (Modifier) -> Unit) {
    var collapsed by rememberSaveable { mutableStateOf(false) }
    var x by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    var y by rememberSaveable { mutableFloatStateOf(16f) }
    BoxWithConstraints(modifier.zIndex(2f)) {
        val density = LocalDensity.current
        val width = minOf(440.dp, (maxWidth - 16.dp).coerceAtLeast(240.dp))
        val height = if (collapsed) 56.dp else minOf(maxHeight - 16.dp, maxOf(320.dp, maxHeight * 0.72f))
        val maxX = with(density) { (maxWidth - width).coerceAtLeast(0.dp).toPx() }
        val maxY = with(density) { (maxHeight - height).coerceAtLeast(0.dp).toPx() }
        val px = (if (x.isNaN()) maxX else x).coerceIn(0f, maxX)
        val py = y.coerceIn(0f, maxY)
        Surface(modifier = Modifier.offset { IntOffset(px.roundToInt(), py.roundToInt()) }.size(width, height).clip(MaterialTheme.shapes.large),
            shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 10.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("浏览器小窗", Modifier.weight(1f).fillMaxHeight().padding(start = 16.dp, top = 16.dp)
                        .semantics { contentDescription = "拖动浏览器小窗" }
                        .pointerInput(maxX, maxY) { detectDragGestures { change, delta ->
                            change.consume()
                            x = ((if (x.isNaN()) maxX else x) + delta.x).coerceIn(0f, maxX)
                            y = (y + delta.y).coerceIn(0f, maxY)
                        } }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = { collapsed = !collapsed }) { Icon(if (collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, if (collapsed) "展开浏览器小窗" else "收起浏览器小窗") }
                    IconButton(onClick = onExpand) { TanukiIcon(TanukiGlyph.Expand, "展开为完整浏览器") }
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭浏览器小窗") }
                }
                HorizontalDivider()
                if (!collapsed) content(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}
