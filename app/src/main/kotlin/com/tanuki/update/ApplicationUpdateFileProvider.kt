package com.tanuki.update

/** 更新APK使用独立provider组件，避免与浏览器下载的authority共用同一个组件身份。 */
class ApplicationUpdateFileProvider : androidx.core.content.FileProvider()
