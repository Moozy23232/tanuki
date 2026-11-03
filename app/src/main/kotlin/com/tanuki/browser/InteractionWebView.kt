package com.tanuki.browser

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.webkit.WebView

/** 软键盘常不产生 KeyEvent；在原生输入连接上记录真实输入，不能靠网页脚本延长授权计时。 */
internal class InteractionWebView(context: Context, private val input: () -> Boolean) : WebView(context) {
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val delegate = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(delegate, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
                input() && super.commitText(text, newCursorPosition)
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
                input() && super.setComposingText(text, newCursorPosition)
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
                input() && super.deleteSurroundingText(beforeLength, afterLength)
            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
                input() && super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            override fun sendKeyEvent(event: KeyEvent?): Boolean = input() && super.sendKeyEvent(event)
            override fun performEditorAction(editorAction: Int): Boolean = input() && super.performEditorAction(editorAction)
        }
    }
}
