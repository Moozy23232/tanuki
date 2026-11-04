# Tanuki 当前状态

本文件是接手入口。先读本文件与最新交接，再读 [上手指南](AGENT-QUICKSTART.md)、[README](../README.md)、任务相关专题和 [安全](SECURITY.md)。源码是实现事实，[测试现状](TEST-REPORT.md) 是已测依据。

## 最新交接

[液态玻璃分支同步与精简](handoffs/20261104-0949-rebase-liquid-main.md)已完成：根目录 `feat/liquid-glass-navigation` 基于 `f02d60b`，颜色提交 `c6b0adf`；用户已授权将液态玻璃精简差异统一提交/推送该功能分支，执行结果以最终回执为准。保留0.1.3/5与安装安全修复，适配SDK36可空信息；精简生成清单、重复交接及许可证全文。本轮双debug/AndroidTest、97 JVM、API36独立包8项回归通过，modern lint0错误/71警告/1提示；本机AVD为 `Tanuki_UI_API36`。以下为上游历史，不计本轮新测。

另一个会话正在核对 DSH 运行时的文件管理限制，仅回答运行限制、不改产品或操作设备；其 `20261103-1937-files-runtime-question.md` 记录尚未落盘，暂不建立文件链接，也不作为本版验证依据。安装器 URI 修正/打包仍按下面的当前记录进行。

[同步main、修复安装器授权并打包v0.1.3](handoffs/20261103-1906-sync-package-012.md)（修复与实际release验证完成）。修复6cfda3e已推送：更新/浏览器使用独立provider组件，保留非导出边界，补ClipData及系统安装器单APK只读有期限授权。最终0.1.3/code5的release JVM97项、release lint0错误/53警告/1提示，以及11类选定34项设备回归全部通过（171.826秒）；包含实际系统确认页、授权回收与覆盖前合成文件读回。按用户最终要求，所有收尾文档统一提交推送后刷新6个交付附件，APK保持已测字节。MIUI真机与既有全屏断言仍未验收。

[PR #1 界面改版验证](handoffs/20261103-1809-pr-test-plan.md)（验证完成，用户自行决定合并）。固定 PR `5e08178` 从最新 main `67216c9` 分出。隔离 Modern/Compat 编译、97项 JVM、lint（0错误）和合成文件覆盖检查通过；62个不同设备用例累计61通过、1项全屏退出系统栏断言在 main 也失败。通知用例首轮因权限缺失失败，正常系统授权后 main/PR 均通过，原失败保留。未发现本 PR 新增功能回归，未合并/提交/推送；远端 PR 的交接指纹缺口须后续同步。本机测试 AVD 最终保留 PR debug 0.1.1/3、通知已授权，正式交付仍是前轮 non-debuggable release。

产物分类已完成并随 `67216c9` 推送：发布、源码、构建准备和历史归档规则见[取包说明](ARTIFACTS.md)，43项宿主回归与原22个文件内容保留证据归入[测试现状](TEST-REPORT.md)。已完成的分类交接与旧构建交接在本次提交前清理；必要历史验证仍保留于测试现状，不依赖 Git 历史。

以下产品功能和 Android 验证属于前轮，本次没有重跑：

- 用户要求已实现：官方登录跳转修复、设置页 Tanuki 更新按钮、未来发布先问版本号再打包、浏览器 30 秒无操作归还 AI。显式撤销不被计时恢复。
- 用户最终更正为 **v0.1.1**，APK `versionName=0.1.1` / `versionCode=3`（不回退内部代码）；前轮授权的打包和源码 commit/push 已完成，**Release 由用户自己发布，Agent 不创建 tag/上传**。
- 后续反馈「GitHub 请求受限」已改为公开 Feed/Release 清单，不调用 GitHub REST API、不要求 token。复用模拟器实际检查已显示「暂无可用于应用内更新的 Release」，不是 API 限流；用户上传同版本 `app-update.json` 才具备后续更新发现条件。
- 最终 v0.1.1 已重新构建、97 项 JVM 通过、debug lint 为 0 error/53 warnings/1 information；实际发布 APK 在同一 AVD 覆盖后 **6 类20 项全过（113.154 秒）**，包括读回覆盖前合成私有文件。此前 debug 19 项与旧 0.1.2 包 20 项是本轮中间验证；不是完整 Linux 环境或真实账号升级验收。

## 当前产品与数据边界

| 项目 | 当前状态 |
|---|---|
| 开发/本次交付包 | `com.tanuki.modern.workspacecheck`；ARM64、minSdk26、targetSdk34、版本 0.1.3/5；开发证书的 non-debuggable release，不是正式签名 |
| 标准 modern | `com.tanuki.modern` 是不同包名；用户先前卸载的旧 `.modern` 不恢复，不能把不同包名或不同证书当覆盖升级 |
| 运行环境 | Ubuntu24.04 ARM64 + Node LTS + npm/pnpm + 官方 DSH；官方 UI/模型/插件/Skills 保留，PRoot 不是恶意代码沙箱 |
| 登录/API Key | 用户本次明确恢复 DeepSeek 登录跳转排查；API Key 保存仍暂停，不调查/导出真实凭据。合成登录回调已测，真实 DeepSeek 账号登录未验收 |
| 应用更新 | 固定仓库公开发布来源、同包/证书/更高 code、真实字节/SHA/SDK/APK 复验、缓存 FileProvider/用户确认；安装前阻止 DSH/组件/文件忙碌，见 [更新说明](APP-UPDATES.md) |
| 环境保留 | 同包同签名覆盖 APK 不自动重装 Ubuntu/Node/DSH；不卸载、清数据、恢复旧快照、迁移签名。完整会话便携迁移仍有限制，见 [更新恢复](UPDATE-RECOVERY.md) |
| 浏览器 | 默认授权当前工具连接；触摸先暂停，临时接管 30 秒无交互归还，显式撤销持久保留；后台本身不撤销。官方登录临时页不给 AI 读写/截图、不保存历史。完整截图/缩放/小窗实现保留 |
| 测试设备 | 用户允许模拟器测试；每台开发机复用一个既有测试 AVD。本机 `Pixel_7_API_34_arm64` / `emulator-5572`，ARM64 API34，无本轮真实任务；不每任新建。首次误建的临时 AVD 已停用，不 wipe/删除数据 |

## 已实现、待验证与暂停

### 已实现与已测

- v0.1.3/5修复已在同一ARM64 API34/WebView159模拟器复现并消除系统安装器provider拒绝；实际release的34项范围、97项JVM和签名/SDK/校验见[本次交接](handoffs/20261103-1906-sync-package-012.md)及[测试现状](TEST-REPORT.md)。只计本轮实际执行范围，不把旧62项或debug结果混作本版release。
- 当前四项功能及 API 限流修复的源码/回归已接入；完整命令、失败修正、计数与设备边界见[产品交接](handoffs/20261103-1316-login-app-update.md)与 [TEST-REPORT](TEST-REPORT.md)。模拟器不等同真机，合成 callback 不等同真实账号登录。
- 既有 DSH 解析优化保留：随包官方固定锁、已核对缓存复用，弱网请求重试/超时与进程组取消，未关闭 TLS/integrity 或把镜像当官方元数据来源。实现细节见 [组件](COMPONENTS.md)，此前验证见 [DSH 修复交接](handoffs/20261103-1051-dsh-resolution-cache.md)，不冒充本轮跑了首次安装/测速。
- Git 前归档必要结论、清理过期交接、修复引用、重查实际暂存和源码 ZIP 的规则已固化于 `AGENTS.md` / [PUBLICATION](PUBLICATION.md)，不按日期盲删仍有效依据。前轮源码交付基线为 `c0680b4`。

### 待验证 / 外部阻塞

- MIUI真机、进程死亡后的授权期限恢复、完整运行环境升级仍待验证。既有退出全屏systemBars断言未在本轮修复，未纳入34项通过，不将局部验收当全产品全绿。
- 真实 DeepSeek 登录、真实模型任务、真机，以及完整 Ubuntu/Node/DSH 环境跨 APK 覆盖尚未本轮验收。
- 用户发布本版清单后，仍需用同包同证书且更高版本的 APK 验证应用内「发现→下载→系统未知来源授权→确认安装」全链；无 Release 时不能造更新成功。
- 正式密钥/签名迁移/生产发布未执行，正式产品不视为已验收；API Key 保存由用户暂停。
- 弱网首次 DSH 安装性能、真实 Cookie/会话/加密备份跨设备迁移未验证，不用本机/`.build` 缓存冒充公共可重建证据。

## 产物、Git 与下一步

- 当前完整附件在 `artifacts/releases/0.1.3/`，共6份，APK为workspacecheck开发签名、non-debuggable、ARM64、0.1.3/code5；`app-update.json`与实际身份/证书/大小/SHA一致。0.1.1已发布附件保持原样。0.1.2/code4中间包归档于 `archive/0.1.2-code4-before-uri-fix/`，不发布；更早撤回候选仍在原archive目录，详见[取包说明](ARTIFACTS.md)。
- 源码准备区为 `artifacts/sources/`，原生对应源码在 `sources/native/`，正式签名中间包在 `builds/production/<版本>/`。发布目录源码与散列须在最后文档写完后刷新，不沿用修复前归档。
- 用户已授权先推送修复再打包。准备提交681f55a、修复提交6cfda3e已推送origin/main；0.1.3从修复提交构建，实际验证结论继续同步收尾。没有创建tag或上传GitHub Release。
- 用户发布时从0.1.3目录上传全部6份附件，尤其app-update.json；以同一源码提交创建v0.1.3并标Pre-release。实际系统安装器读取已用AOSP合成路径验证，MIUI真机仍需用户复验；旧版不能倒过来应用新URI修复，首次可从系统文件管理器手动覆盖，不卸载或清数据。
- 下一任建立新交接、复用同一AVD，确认无安装/真实任务后再测；既有全屏退出断言和真实环境/账号/弱网未测边界继续保留，不恢复API Key保存排查。
