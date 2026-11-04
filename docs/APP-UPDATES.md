# 应用内更新

入口：**设置 → 应用更新 → 检查更新**。这里更新 Tanuki APK，不是「组件维护与诊断」中的 DSH/Ubuntu/Node 更新。

## 来源与检查

- 固定读取 `https://github.com/leezzou/tanuki` 的公开 Release 信息，**不调用 GitHub REST API，不需要 GitHub token**。避免匿名 API 额度导致「请求受限」。
- `workspacecheck` / debug 渠道读取公开 `releases.atom` 中最近最多 10 个 tag，再取同一 tag 的 `app-update.json`，包括公开 Pre-release。正式渠道只读取 `releases/latest/download/app-update.json`，从 GitHub 的稳定版跳转取得明确 tag，不跟随预发布。
- 只允许本仓库的合法 tag/附件名、HTTPS 与 GitHub 官方附件 CDN；限制重定向、单份元数据 1 MiB、最多 8 个包条目。未发布清单（404）、没有更高兼容版本、网络/发布页失败分别反馈，不能把「没有 Release」当成「已是最新版」。
- 清单只声明预期包名、版本、SDK、附件名、大小、SHA-256 和证书指纹；应用用固定仓库/tag 构造附件 URL，不信任任意镜像 URL。公开清单不是独立发布者签名，最终还须核对 APK 的实际身份和 Android 安装签名。

实现：[`ApplicationUpdateManager.kt`](../app/src/main/kotlin/com/tanuki/update/ApplicationUpdateManager.kt)、[`AppReleaseManifest.kt`](../app/src/main/kotlin/com/tanuki/update/AppReleaseManifest.kt)、[`AppUpdatePolicy.kt`](../app/src/main/kotlin/com/tanuki/update/AppUpdatePolicy.kt)。

## 下载与安装

1. 检查当前安装的实际包名/证书，仅接受同包名、同签名证书、适配当前 Android 且 `versionCode` 更高的候选。不跨 `com.tanuki.modern.workspacecheck` / `com.tanuki.modern` 迁移。
2. 用户点击「下载更新」才下载，使用应用专属 CDN 范围、实际大小/字节进度、耗时、取消和 SHA-256；单 APK 最多 512 MiB。未知总量/检查/校验阶段是不定进度，不编造百分比。
3. 文件只放 `cacheDir/application-updates`，验证目录/文件归属与链接，下载后及安装前重新检查真实 APK 包名、版本、最低 SDK、签名证书和字节哈希。主线程不做网络、磁盘哈希或 APK 解析。
4. 安装前要求 DSH 已停止、没有组件安装/更新、真实子进程和文件任务；如果环境忙碌，保留已下载文件，提示先停止。检查/下载通过前台任务显示状态，不自动中断真实工作。
5. 更新与浏览器下载分别使用独立的非导出FileProvider子类，保持原authority和目录范围，避免两个authority共用同一组件身份。更新provider仅共享已经复验的APK。Intent同时带data、ClipData和只读标记；仅向实际匹配APK的系统安装处理器补充单文件只读授权，并使用解析到的系统组件，兼容MIUI安装器在Activity启动前的预读。不授予普通APK关联应用，不申请全量应用可见性。需要用户确认安装；未获「安装未知应用」许可时先由用户在系统设置授权，再明确重试，不偷偷自动安装。系统还会校验 APK 的真实签名与安装兼容性。
6. 校验/网络失败或取消不清除现有 App 和环境，不卸载、不 `pm clear`，也不自动回滚用户数据。

补充读取授权最多10分钟，下一次检查/下载/安装准备或启动安装器失败时收回；只保存本应用更新URI与到期时刻，进程重建后继续按原期限回收，不自动重放安装。安装器可能先把读取工作交给后台组件，不能依赖Activity立即返回就撤销授权。实现见 `update/ApkInstallAccess.kt`，FileProvider仍为 `exported=false`，不扩大可共享目录。

旧版本发起安装时的授权逻辑无法由待安装APK倒过来修改。如旧版本已遇到MIUI拒绝读取，可将新APK保存到系统下载目录，使用系统文件管理器手动覆盖一次；保持同包同证书，不卸载或清数据。后续由修复版发起更新使用新的授权链。MIUI真机仍需复验，AOSP模拟器结果不冒充其通过。

实现：[`Downloader.kt`](../app/src/main/kotlin/com/tanuki/download/Downloader.kt)、[`AppUpdateFiles.kt`](../app/src/main/kotlin/com/tanuki/update/AppUpdateFiles.kt)、[`MainActivity.kt`](../app/src/main/kotlin/com/tanuki/MainActivity.kt)、[`update_file_paths.xml`](../app/src/main/res/xml/update_file_paths.xml)。

同包同证书覆盖安装保留私有 Ubuntu/Node/DSH、项目与配置；仅更新 APK 不触发重装组件。安装失败时不要先卸载旧 App，详见 [组件边界](COMPONENTS.md#升级-tanuki-apk-与组件重装) 和 [安全](SECURITY.md)。

## 发布者必须提供什么

未来用户说「发布」时，Agent **先问版本号**（已经明确则不重复问），确认递增的 `versionCode` 和渠道后再打包。本次用户确认 `v0.1.5`，对应 `versionName=0.1.5` / `versionCode=7`，沿用原workspacecheck开发证书。打包、源码提交/推送、创建 tag、上传 Release 是不同授权；本次授权测试正常后push并在本地准备附件，Release由用户发布。

```bash
source scripts/env.sh
python3 scripts/prepare-app-release.py --version 0.1.5 --version-code 7 --channel workspacecheck
```

脚本验证实际 APK 包名、版本、SDK、证书和文件字节，生成 `artifacts/releases/0.1.5/`：

- `Tanuki-0.1.5-workspacecheck-arm64-release.apk`：开发证书、non-debuggable 的测试发布包；GitHub 应标 Pre-release。
- **`app-update.json`**：脚本生成的更新清单，必须与 APK 上传到 tag `v0.1.5` 的同一个 Release，不能只上传 APK。
- `RELEASE-SHA256SUMS`、完整 `Tanuki-0.1.5-source.zip`、原生对应源码与校验文件。

清单结构为 `schemaVersion:1` / `packages` 数组，每个包包含 `packageName`、`versionName`、整数 `versionCode/minSdk/bytes`、`assetName`、`sha256`、`certificateSha256`。不要把文档示例或其他包/版本的元数据当成实际附件。更换/重打 APK 后必须重跑生成和外部散列检查。

正式渠道使用 `--channel production` 与仓库外的正式密钥，包名不同且不能覆盖当前测试包。完整步骤见 [发布说明](RELEASE.md)。

## 验证范围

本轮对公开 Feed、清单、来源/大小/哈希、APK 身份、缓存文件与签名边界补回归。已在复用 ARM64 API34 模拟器实际进入更新页并点击检查，结果为「暂无可用于应用内更新的 Release」，而非 API 限流。网络始终不可达时仍会失败，不承诺 GitHub 全球可用。

真实未来 Release 的发现→下载→系统授权→安装完整链仍需在用户上传本版清单及更高版本 APK 后验收；合成私有文件跨覆盖保留、模拟器安装不等于真实账号/真机/全套运行环境升级。最新结果见 [测试现状](TEST-REPORT.md)。
