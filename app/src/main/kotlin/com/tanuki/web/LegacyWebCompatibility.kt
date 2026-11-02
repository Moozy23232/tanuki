package com.tanuki.web

import android.content.Context
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.tanuki.runtime.RuntimeManager

/** ECMAScript API shim; no DOM selectors or modifications of DSH application logic. */
class LegacyWebCompatibility(private val context: Context) {
    private var script: ScriptHandler? = null
    fun install(view: WebView, origin: String, dshVersion: String) {
        script?.remove(); script = null
        val enabled = context.getSharedPreferences("web", Context.MODE_PRIVATE).getBoolean("legacyPromise", true)
        val webVersion = WebView.getCurrentWebViewPackage()?.versionName?.substringBefore('.')?.toIntOrNull() ?: return
        if (!enabled || webVersion >= 119 || dshVersion != RuntimeManager.DSH) return
        check(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) { "此 WebView 过旧且不支持启动脚本，请在系统设置更新 Android System WebView" }
        script = WebViewCompat.addDocumentStartJavaScript(view, PROMISE_WITH_RESOLVERS + ABORT_SIGNAL_ANY, setOf(origin))
    }
    fun dispose() { script?.remove(); script = null }
    companion object {
        const val ABORT_SIGNAL_ANY = """
            if (typeof AbortSignal.any !== 'function') {
              Object.defineProperty(AbortSignal, 'any', {
                configurable: true, writable: true,
                value: function any(signals) {
                  const sources = Array.from(signals);
                  for (const signal of sources) {
                    if (!(signal instanceof AbortSignal)) throw new TypeError('Expected AbortSignal');
                  }
                  const controller = new AbortController();
                  const listeners = [];
                  const finish = (source) => {
                    if (controller.signal.aborted) return;
                    controller.abort(source.reason);
                    for (const [signal, listener] of listeners) signal.removeEventListener('abort', listener);
                    listeners.length = 0;
                  };
                  for (const source of sources) {
                    if (source.aborted) { finish(source); return controller.signal; }
                  }
                  for (const source of sources) {
                    const listener = () => finish(source);
                    listeners.push([source, listener]);
                    source.addEventListener('abort', listener, { once: true });
                  }
                  return controller.signal;
                }
              });
            }
        """
        const val PROMISE_WITH_RESOLVERS = """
            if (typeof Promise.withResolvers !== 'function') {
              Object.defineProperty(Promise, 'withResolvers', {
                configurable: true, writable: true,
                value: function withResolvers() {
                  let resolve, reject;
                  const promise = new this((res, rej) => { resolve = res; reject = rej; });
                  return { promise, resolve, reject };
                }
              });
            }
        """
    }
}
