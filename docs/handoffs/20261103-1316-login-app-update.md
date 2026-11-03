# 交接：DeepSeek 登录跳转、发布版本确认与应用内更新

- Agent/会话标识：主 Agent / login-app-update
- 时间：2026-11-03 13:16 CST 开始；本次实现与打包完成
- 状态：功能、最终 v0.1.1 打包及模拟器验证均完成；仅按授权源码 commit/push，不代发 Release
- 开始基线：`main` / `origin/main` 为 `c0680b4`，接手工作树干净；旧流程交接必要规则已归入专题后按本次用户要求清理。

## 用户目标与范围

- 修复 DeepSeek 登录跳转；本次明确恢复登录排查，API Key 保存仍暂停，不读取真实凭据。
- 未来用户说发布时先问版本号再打包；用户起初指定 v0.1.2，最终明确更正为 **v0.1.1**，采用 `versionName=0.1.1` / `versionCode=3`（内部代码不回退），所有交付附件重打。
- 设置页新增 Tanuki APK 更新按钮；后续反馈「GitHub 请求受限」一起修复，不要求 token。
- 浏览器 30 秒无点击/滑动/键盘/输入，临时接管默认交回同一当前 AI 连接；显式撤销永久保留到用户主动恢复。
- 用户允许模拟器并要求写入文档；进一步要求每台开发机复用同一既有 AVD，不得每任新建。本轮复用 `Pixel_7_API_34_arm64` / `emulator-5572`。
- 用户最后明确：改完打包后 `git commit push`，由用户自己发 Release。仅源码提交/推送已授权，不创建 tag/Release 或上传附件。

## 当前状态

- 上述功能源码与回归已接入；当前应用版本 0.1.1/3，验证/交付包 `com.tanuki.modern.workspacecheck`，SDK26/34、ARM64、原开发证书；标准 `.modern` 是另一个包，不恢复/覆盖。
- 官方 DSH UI 保留。登录仅放行当前认证管理页发起的官方授权与精确 loopback `/callback`，一次性内存票据和私有临时标签，不把账号 Cookie/管理 token 交给 AI。
- 应用更新不再请求 GitHub API：测试渠道公开 Atom/最多 10 tag，生产只 latest 稳定清单；元数据只提供预期值，实际 APK 仍做同包/同证书/更高版本、SDK、大小、SHA 及文件归属检查，Android 系统授权/确认安装不绕过。
- 安装前检查运行/组件/文件忙碌；失败/取消不改现有环境。30 秒计时明确区分临时接管和 sticky revoke，并覆盖触摸/键盘/地址栏/原生工具栏/输入。

## 本次改动

| 文件 | 改动与目的 |
|---|---|
| `web/DshOAuth.kt`、`web/DshWebView.kt`、`browser/AgentBrowserController.kt`、`MainActivity.kt` | 官方授权入口/预开空白弹窗/异步导航、严格一次性回调、原生转交、私有登录标签及失败反馈 |
| `browser/BrowserIdleReturnPolicy.kt`、`browser/InteractionWebView.kt`、`ui/AgentBrowserPage.kt`、`ui/TanukiUi.kt`、`ui/ApplicationUpdateCard.kt` | 30 秒空闲归还、跨触摸/输入交互复位、计时反馈与显式撤销保留 |
| `update/ApplicationUpdateManager.kt`、`AppUpdatePolicy.kt`、`AppUpdateFiles.kt`、`AppReleaseManifest.kt`、`download/Downloader.kt` | APK 检查/下载/进度/取消/校验/安装；无 API 发布发现、官方 CDN 与元数据/字节/身份边界 |
| `TanukiApp.kt`、`runtime/RuntimeService.kt`、`AndroidManifest.xml`、`res/xml/update_file_paths.xml` | 生命周期/前台任务/忙碌边界、未知来源安装权限、专属只读 FileProvider |
| `app/build.gradle.kts`、`scripts/env.sh`、`scripts/sign-release.sh`、`prepare-app-release.py`、`package-source.py` | 显式版本参数、0.1.1/3 默认、进程环境导出、测试/正式渠道与真实身份核对、发布附件生成 |
| `app/src/test/kotlin/com/tanuki/` 中 OAuth/空闲/更新相关测试；`AgentBrowserControlTest.kt`、`WebAuthenticationTest.kt`、`ApplicationUpdateNativeTest.kt` | 业务改动前补回归；合成重放/错误来源/文件归属/字节/APK 安全/权限与 UI/覆盖文件 |
| `scripts/test-app-release.py`、`test-agent-workflow.py` | 版本拒绝、真实 APK 字段、清单/散列、外部密钥路径/符号链接和工作流回归 |
| `AGENTS.md`、状态/上手/发布/应用更新/安全/浏览器/组件/测试文档与本记录 | 固化发布先问版本、每机复用 AVD、用户自行发布与验证边界；过期交接归档/删后修引用 |

### Git 前清理过期交接

- 删除 `docs/handoffs/20261103-1154-handoff-retention.md`：清理过程已经完成，长期规则保留于 `AGENTS.md` / `docs/PUBLICATION.md`，历史宿主验证留在 `docs/TEST-REPORT.md`，Git 基线 `c0680b4` 已保留于当前状态。
- 删除 `docs/handoffs/20261103-1158-release-publishing.md`：旧版发布咨询、未决版本/tag 和未签名路径核对已被本版打包替代；签名/包名/不代发/GPL 附件边界均归入 `docs/RELEASE.md`，历史 unsigned 验证留在测试报告。
- 当时保留的旧构建记录在后续0.1.2整理时已将必要构建/迁移证据归入[测试现状](../TEST-REPORT.md)并清理；DSH修复记录 `20261103-1051-dsh-resolution-cache.md` 仍保留组件锁与未测性能边界，不按日期删除。

## 验证记录

| 实际命令 / 操作 | 环境与结果 | 证据与限制 |
|---|---|---|
| 先添加新回归，再实现纯策略/接入 | 新类尚未实现时红灯，随后测试通过 | 未把初始编译失败记为通过 |
| `./gradlew --no-daemon -PtestBuildType=debug -PdevelopmentReleaseSigning=true -I scripts/workspace-verification.init.gradle :app:assembleModernDebug :app:testModernDebugUnitTest :app:lintModernDebug :app:assembleModernDebugAndroidTest` | 最终 0 退出；97 项 JVM / 0 fail / 0 skip；debug lint 0 error /53 warnings/1 information；含 UsableSpace、RequiresFeature、GradleDependency、交互 WebView/Compose 等提示，未宣称全部既有或清零 | 逐次阶段日志只在 `.build`；全部计数来自本轮 XML/日志，不复用旧报告 |
| `python3 scripts/test-app-release.py`、`test-agent-workflow.py`、`test-publication.py`；`bash -n scripts/sign-release.sh` | 9+12+19 项宿主回归通过，shell 语法通过 | 合成 APK badging/证书字段和静态工作流边界，不冒充真实正式签名 |
| 启动/检查 `Pixel_7_API_34_arm64` / `emulator-5572`，`adb install -r` debug/测试 APK；明确核对版本/target、无 apt/dpkg/Node/安装任务 | 复用 ARM64 API34；既有 WebView159；只合成测试数据，无真实任务 | 起初误建 `Tanuki_0_1_2_API_34_arm64` /5570，随后停用；不 wipe/恢复快照/删除数据，规则已纠正 |
| `adb -s emulator-5572 shell am instrument -w -r -e class com.tanuki.AgentBrowserControlTest,com.tanuki.AppBrandingTest,com.tanuki.ApplicationUpdateNativeTest,com.tanuki.ApplicationUpdatePresentationTest,com.tanuki.BrowserIntegrationTest,com.tanuki.WebAuthenticationTest com.tanuki.modern.workspacecheck.test/androidx.test.runner.AndroidJUnitRunner` | 最终 debug **OK (19 tests)**；没有 adb 断线/host timeout | 包含原生 UI/主题/更新按钮/浏览器权限/合成官方弹窗和 callback/30 秒真实计时；真实账号未登录 |
| 模拟器原生设置实际点击「检查更新」并读取无敏感信息的原生 UI 状态 | 显示「暂无可用于应用内更新的 Release；发布时需附带 app-update.json」 | API 限流不再出现；公网已有旧 tag，但没有本版应用清单，不能伪造可更新 |
| 新增私有合成覆盖文件回归后重编 AndroidTest，单独运行 `ApplicationUpdateNativeTest` | **OK (3 tests)**，为发布 APK 覆盖种已知合成文件 | 之后须用 `verifyUpgradeMarker=true` 验证读旧值，不允许重建冒充保留 |
| `python3 scripts/prepare-app-release.py --version 0.1.2 --version-code 3 --channel workspacecheck` | 成功；release 构建 103.8秒、原生对应源码打包18.3秒，输出6个附件；实际包0.1.2/3、SDK26/34、仅ARM64、无 application-debuggable | 开发证书 non-debuggable release；不上传、不建 tag |
| `adb -s emulator-5572 install -r artifacts/releases/0.1.2/Tanuki-0.1.2-workspacecheck-arm64-release.apk` 后重跑上述6类（加 `-e verifyUpgradeMarker true`） | **OK (20 tests)**、104.346秒，无失败/跳过/断线；合成覆盖前私有文件读回原值后删除 | 实际发布 APK 字段/证书、浏览器/登录/UI再验；不是完整已装 Linux 环境迁移 |
| 版本更正后串行重建 release/AndroidTest、JVM、debug lint；`python3 scripts/prepare-app-release.py --version 0.1.1 --version-code 3 --channel workspacecheck` | 构建与打包成功，JVM97项/0失败/0跳过，lint仍0错误/53警告/1提示；实际APK 0.1.1/3、SDK26/34、仅ARM64、non-debuggable、开发证书不变 | 初次等待120秒未结束，只等待同一构建；未并行或中断Gradle，最终退出0 |
| 重新运行覆盖标记单例1项；安装最终 `artifacts/releases/0.1.1/Tanuki-0.1.1-workspacecheck-arm64-release.apk`；重跑上述6类并加 `verifyUpgradeMarker=true` | **OK (20 tests)**、113.154秒；标记读回、实际APK字段/证书、UI/浏览器/合成登录全部通过；主机退出0 | 复用同一模拟器，不把旧0.1.2结果作为最终包验证 |
| `check-handoff.py --record` 与无参数、Markdown 链接/公开边界、源码 ZIP/外部 SHA/独立解压/index 检查 | 全部通过：98个本地文件链接；Git候选278/index278、源码/实际APK扫描均0发现；全部外部SHA匹配；独立解压交接/公开边界通过且无2份过期记录 | 公共指纹277文件，源码ZIP280条目；收尾文字后再次刷新/重打/复查，Git只提交公开源码 |

## 失败、修正与未测试范围

- 辅助调查采用只读 `run_agent` 并取得官方公开 OAuth 静态协议结论；前轮 `acp_delegate` 的宿主 Node 路径错误不重试、不依赖缺失结果。
- 原生测试首次编译引用了不存在的 `signerSha256`，且 `AppUpdateAsset` 参数顺序错误；改为 `signers` 和命名参数后本轮完整构建通过。
- 首次 18 项中合成 WebView 等待超时 1 项；加阶段诊断后单例复跑通过。更新页合成测试曾因回调未等/小窗遮挡失败，改为主线程调用并关测试小窗；最终 19 项全套通过，未放宽权限/来源断言。
- 首次查询复用 AVD 时 Android package service 尚未启动，返回 `Can't find service: package`；只等待启动后重查，没有清数据。错误新建的临时 AVD 已停用，后续只复用既有 AVD。
- 发布 APK 独立 badging 核验命令误读未导出的 `TANUKI_SDK`，出现 `KeyError: 'TANUKI_SDK'`，在安装前退出；改用脚本已导出的 `ANDROID_HOME` 后核验/覆盖/20项均通过，没有装错包。
- 公开检查首次误传不支持的 `--index`，argparse退出2；实际 `--git` 已同时检查工作树候选和 index。随后2处合成 URL userinfo 字面量触发 `review-email`，改用显式合成用户名与 `@` 字符构造同样攻击 URL，未忽略/放宽扫描规则或削弱拒绝断言；重跑 JVM、重新暂存/打包后再查。
- 外部源码SHA首次在仓库根目录核对导致相对文件名找不到；改为 `(cd artifacts && shasum -a 256 -c SOURCE-SHA256SUMS)` 后全部匹配，没有忽略散列错误。
- 最终归档核对发现发布目录的原生 SHA 清单仍含 `artifacts/` 前缀，且源码 ZIP 根目录变量被遍历文件名覆盖；各补一项红灯回归后修正，发布准备共9项通过。发布目录清单改为本目录文件名，ZIP 根目录固定为确认版本；不更改公共源码树内原生清单的正确路径。
- 未使用真实 DeepSeek 账号/密钥/会话、未跑真实模型、未测真机、未首次安装 Linux 环境/弱网 DSH 性能、未验未来更高 Release 的完整系统授权安装链。API Key 保存仍暂停。

## 未完成与暂停项

### 关键决策

- 只为官方 OAuth 特例解除异步/空白弹窗阻断；普通外链仍需用户手势且不进入管理 origin。一次性票据绑定当前运行身份，超时/重放/错误端口/路径拒绝；完成时走原生精确回调而非让网页接触管理 host。
- 应用更新不引入第三方下载代理/镜像，不关闭 TLS/哈希/签名；公开 Feed 避开 API 额度但 GitHub 网络不可达仍报错。无清单与没有兼容更高版本分别反馈。
- 显式撤销高于空闲计时；AI 只能获得新的当前连接许可，旧票据不复活。主线程不做大文件哈希/网络，进度真实。
- 本轮版本已明确，不自定新的正式版本。真实账号登录、未来 Release 更高版本安装、正式证书/生产迁移均待用户条件；不以卸载/清数据解决签名或降级。

## 下一步

1. v0.1.1 已重打并通过实际 APK 20 项复验、公开边界/外部SHA/独立归档检查；旧 v0.1.2 仅作本轮历史验证，不交付。任何后续源码/文档改动都需刷新印章、源码 ZIP/目录副本与散列。
2. 本次 Git 前已清理2份过期流程交接，仍有效构建/DSH依据保留；显式暂存并检查 index 后按用户授权 commit/push `origin/main`。本记录不嵌入自己的提交ID，执行回执报告最终 hash/远端结果。
3. 用户按 [发布说明](../RELEASE.md) 建 `v0.1.1` Pre-release 并上传发布目录 6 个附件。Agent 不代发。
4. 后续在同包同证书且更高 code 的真实发布后验收发现→下载→未知来源授权→系统确认安装；另行验真实 DeepSeek 登录，仍不扩展 API Key 保存排查。

## 约束与风险

### 数据与产物边界

- 所有测试都是公开/合成数据；私有原始诊断留 `.build`、不得直接打包。没有 wipe、卸载、`pm clear`、恢复快照或终止其他应用/全部 Node。
- 唯一本轮目标 `com.tanuki.modern.workspacecheck`；开发证书并非正式签名。根目录旧 APK 不是本版交付，当前附件目录 `artifacts/releases/0.1.1/`，含实际 APK、`app-update.json`、完整源码/原生对应源码及外部 SHA。
- 源码/文档后续变化必须再重打 ZIP 并更新发布目录副本；不把 ZIP 自身哈希写回归档制造循环。没有正式密钥/签名迁移或 Release 发布。

## 产物与校验

- 最终交付为 `artifacts/releases/0.1.1/` 的6个附件，APK实际大小/SHA以该目录 `app-update.json` / `RELEASE-SHA256SUMS` 为准；开发证书SHA-256 `371dc301e7bb4d5cb2840510855f191eeb2f1ce4d058618361419409654cd84f`。旧0.1.2和根目录历史APK不交付。
- 最终交接/源码包/外部 SHA/index 检查完成后再按授权源码 commit/push；提交 ID 与远端结果在最终执行回执明确，不在记录中嵌入自身 hash。
- 真实账号/完整环境/未来更高版本系统安装链不计通过，Release 由用户自己发布。
