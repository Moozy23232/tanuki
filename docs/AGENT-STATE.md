# Tanuki 当前状态

本文件是接手入口。先读本文件与最新交接，再读 [上手指南](AGENT-QUICKSTART.md)、[README](../README.md)、任务相关专题和 [安全](SECURITY.md)。源码是实现事实，[测试现状](TEST-REPORT.md) 是已测依据。

## 最新交接

另一个会话正在核对 DSH 运行时的文件管理限制，仅回答运行限制、不改产品或操作设备；其 `20261103-1937-files-runtime-question.md` 记录尚未落盘，暂不建立文件链接，也不作为本版验证依据。安装器 URI 修正/打包仍按下面的当前记录进行。

[同步main、修复安装器授权并打包v0.1.3](handoffs/20261103-1906-sync-package-012.md)（进行中）。code4中间包/29项设备已归档。用户反馈设置更新FileProvider拒绝后，已补独立provider组件、ClipData与系统安装器只读有期限授权；复现的系统安装页拒绝已在AOSP模拟器消除，5项新增回归通过，97项JVM与debug lint0错误通过。用户确认最终0.1.3/code5；正在检查并推送修复，随后构建最终release。MIUI真机未测试，既有全屏断言未修复。

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
| 开发/本次交付包 | `com.tanuki.modern.workspacecheck`；ARM64、minSdk26、targetSdk34、版本 0.1.1/3；开发证书的 non-debuggable release，不是正式签名 |
| 标准 modern | `com.tanuki.modern` 是不同包名；用户先前卸载的旧 `.modern` 不恢复，不能把不同包名或不同证书当覆盖升级 |
| 运行环境 | Ubuntu24.04 ARM64 + Node LTS + npm/pnpm + 官方 DSH；官方 UI/模型/插件/Skills 保留，PRoot 不是恶意代码沙箱 |
| 登录/API Key | 用户本次明确恢复 DeepSeek 登录跳转排查；API Key 保存仍暂停，不调查/导出真实凭据。合成登录回调已测，真实 DeepSeek 账号登录未验收 |
| 应用更新 | 固定仓库公开发布来源、同包/证书/更高 code、真实字节/SHA/SDK/APK 复验、缓存 FileProvider/用户确认；安装前阻止 DSH/组件/文件忙碌，见 [更新说明](APP-UPDATES.md) |
| 环境保留 | 同包同签名覆盖 APK 不自动重装 Ubuntu/Node/DSH；不卸载、清数据、恢复旧快照、迁移签名。完整会话便携迁移仍有限制，见 [更新恢复](UPDATE-RECOVERY.md) |
| 浏览器 | 默认授权当前工具连接；触摸先暂停，临时接管 30 秒无交互归还，显式撤销持久保留；后台本身不撤销。官方登录临时页不给 AI 读写/截图、不保存历史。完整截图/缩放/小窗实现保留 |
| 测试设备 | 用户允许模拟器测试；每台开发机复用一个既有测试 AVD。本机 `Pixel_7_API_34_arm64` / `emulator-5572`，ARM64 API34，无本轮真实任务；不每任新建。首次误建的临时 AVD 已停用，不 wipe/删除数据 |

## 已实现、待验证与暂停

### 已实现与已测

- 当前四项功能及 API 限流修复的源码/回归已接入；完整命令、失败修正、计数与设备边界见[产品交接](handoffs/20261103-1316-login-app-update.md)与 [TEST-REPORT](TEST-REPORT.md)。模拟器不等同真机，合成 callback 不等同真实账号登录。
- 既有 DSH 解析优化保留：随包官方固定锁、已核对缓存复用，弱网请求重试/超时与进程组取消，未关闭 TLS/integrity 或把镜像当官方元数据来源。实现细节见 [组件](COMPONENTS.md)，此前验证见 [DSH 修复交接](handoffs/20261103-1051-dsh-resolution-cache.md)，不冒充本轮跑了首次安装/测速。
- Git 前归档必要结论、清理过期交接、修复引用、重查实际暂存和源码 ZIP 的规则已固化于 `AGENTS.md` / [PUBLICATION](PUBLICATION.md)，不按日期盲删仍有效依据。前轮源码交付基线为 `c0680b4`。

### 待验证 / 外部阻塞

- 真实 DeepSeek 登录、真实模型任务、真机，以及完整 Ubuntu/Node/DSH 环境跨 APK 覆盖尚未本轮验收。
- 用户发布本版清单后，仍需用同包同证书且更高版本的 APK 验证应用内「发现→下载→系统未知来源授权→确认安装」全链；无 Release 时不能造更新成功。
- 正式密钥/签名迁移/生产发布未执行，正式产品不视为已验收；API Key 保存由用户暂停。
- 弱网首次 DSH 安装性能、真实 Cookie/会话/加密备份跨设备迁移未验证，不用本机/`.build` 缓存冒充公共可重建证据。

## 产物、Git 与下一步

- 有效发布只在 `artifacts/releases/0.1.1/`，仍为6个附件且 APK/更新清单不变。根目录散落旧包已迁至 `artifacts/archive/`，被撤回的0.1.2不再放在有效发布目录；原22个文件内容全部保留。完整目录与校验方式见[取包说明](ARTIFACTS.md)，生成副本为 `artifacts/README.md`。
- 源码打包输出改为 `artifacts/sources/`，原生对应源码在 `sources/native/`，正式签名中间包在 `builds/production/<版本>/`；脚本已同步，防止下次重新散落。源码 ZIP 和当前发布目录源码副本随文档重打，检查结果见最新交接。
- 前轮源码已推送 `origin/main`（`50da4b3`），没有创建 tag 或发布 Release；本轮目录整理收尾时用户已明确要求 push，按授权提交脚本/文档；产物继续由用户自行上传，Agent 不发布 Release。
- 后续按 [发布步骤](RELEASE.md) 由用户上传同一 tag 的 APK、`app-update.json`、完整源码/原生对应源码与校验文件。遇签名/版本不兼容不卸载旧 App 绕过。
- 下一任新建唯一交接，复用上述 AVD，先确认无安装/真实任务再测试；不要重启已暂停的 API Key 工作。必要专题入口见 [上手指南](AGENT-QUICKSTART.md)。
