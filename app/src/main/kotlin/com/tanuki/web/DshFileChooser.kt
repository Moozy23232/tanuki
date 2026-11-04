package com.tanuki.web

import android.content.Context
import android.content.Intent
import android.app.Activity
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import java.util.Locale

/** 只向管理页交付用户从文档选择器选出的外部内容，不开放 App 自有文件。 */
internal object DshFileChooser {
    fun result(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        // WebView 的通用 parseResult 只读取 data；SAF 多选及部分选择器仅返回 ClipData。
        val selected = linkedSetOf<Uri>()
        data.data?.let(selected::add)
        data.clipData?.let { clips ->
            repeat(clips.itemCount) { index -> clips.getItemAt(index).uri?.let(selected::add) }
        }
        return selected.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    fun intent(params: WebChromeClient.FileChooserParams): Intent {
        val types = params.acceptTypes.flatMap { it.split(',') }.mapNotNull { raw ->
            val value = raw.trim().lowercase(Locale.ROOT)
            if (value.startsWith('.')) MimeTypeMap.getSingleton().getMimeTypeFromExtension(value.drop(1))
            else value.takeIf { it.matches(Regex("[a-z0-9!#$&^_.+*-]+/[a-z0-9!#$&^_.+*-]+")) }
        }.distinct().ifEmpty { listOf("*/*") }
        return Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(types.singleOrNull() ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
            .apply { if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray()) }
    }

    fun selectedUris(context: Context, uris: Array<Uri>?, multiple: Boolean): Array<Uri>? {
        if (uris.isNullOrEmpty() || (!multiple && uris.size != 1)) return null
        // 第三方文档选择器也可能伪造结果，不能把 App 本身的读权限转交网页。
        if (uris.any { uri ->
                uri.scheme != "content" || uri.authority.isNullOrBlank() || uri.userInfo != null ||
                    runCatching { context.packageManager.resolveContentProvider(uri.authority!!, 0)?.applicationInfo?.uid }
                        .getOrNull().let { it == null || it == context.applicationInfo.uid }
            }) return null
        return uris.distinct().toTypedArray()
    }
}
