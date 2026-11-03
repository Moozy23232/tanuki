# 测试现状

Tanuki验证目标：`com.tanuki.modern.workspacecheck`，命名空间 `com.tanuki`，ARM64、minSdk26/targetSdk34。debug/release构建与证书、宿主与设备执行必须分别记录，源码中有测试不等于已运行。

## v0.1.2/4 发布准备（2026-11-03，打包进行中）

- 用户确认0.1.2并要求先推送本地源码、再打包。主目录已从67216c9快进至用户合并PR后的ece5c82，合并代码树与已测PR 5e08178一致；仅将版本默认值/源码包名更新至0.1.2/4，补齐实际58项依赖及上游许可、同步交接/发布文档，无业务行为修复。
- 公开v0.1.1/v0.1.0附件的GitHub SHA-256与本地旧APK一致，apkanalyzer实际code为3/1，设备与撤回候选均为3；新版code4单调递增，沿用原workspacecheck开发证书。
- 本轮主目录新跑：发布12项、工作流12项、公开边界19项宿主回归通过；97项JVM无失败/错误/跳过，debug lint 0错误/53警告/1提示，Gradle 1分57秒成功。112个本地Markdown链接与diff格式检查通过。
- release构建、实际APK身份/校验及设备覆盖验证尚待推送完成后执行；前轮61/62设备结论不能当本次新包结果。完整进度见[本次交接](handoffs/20261103-1906-sync-package-012.md)。

## PR #1 界面改版隔离验证（2026-11-03，验证时尚未合并）

- 固定 [PR #1](https://github.com/leezzou/tanuki/pull/1) head `5e081784707352f35564890ad139b50580190661`，共同祖先为用户最新 main `67216c9561294e196d5e678c381b09888bc61e30`，ahead2/behind0；收尾远端引用仍一致。两个独立 worktree 的产品代码均保持各自提交原样，主工作区只记验证文档，不自动合并。
- PR 的 `verify-workspace.sh` 构建阶段通过（3分37秒）：Modern debug app/test、97项 JVM（0失败/错误/跳过）、lint 0错误/53 warnings/1 information。另顺序编译 Compat debug 通过（1分9秒），未安装不同包名或在旧 Android 上运行。
- 复用 `Pixel_7_API_34_arm64` / `emulator-5572`，ARM64 API34、WebView159.0.8133.0、专用合成数据，无安装/真实任务。实际 APK 包名、test target、开发证书一致后同包覆盖；覆盖前、PR安装后及main/PR相互覆盖后的合成文件读回均通过。最终保留 PR debug v0.1.1/3，不是前轮发布 APK 或正式签名包。
- PR 首轮受影响 UI 组22项：21通过/1失败（177.584秒）；文件/浏览器安全扩展组40项：39通过/1失败（160.375秒）。覆盖玻璃背景像素模糊/点击/RTL/200%字体、安装步骤与进度、文件编辑复制多选、设置与更新入口、浏览器多页/收藏/历史/搜索/下载、触摸暂停/30秒归还/显式撤销、整页尾部截图/AI缩放/小窗、WebView保留、合成外链/认证与Blob等；未运行依赖已装运行环境的两个用例，不把未选择算通过。
- 失败对照：`BrowserWorkspacePresentationTest#newBrowserPageDefaultsToWindowsAndFullscreenKeepsTheDocument` 在第227行退出全屏后的 `systemBars()` 可见性等待失败，PR 单独复跑仍失败（23.799秒），main 同点失败；根因未修复，不宣称该功能已通过。`BrowserOverlayPresentationTest#displayDensityKeepsCollapsedControlsReachableAndNotificationStopIsFinal` 第303行通知动作缺失，main也同点失败（两项对照共30.540秒）。设备通知权限原为关闭，正常点击原生入口和系统「允许」后，此通知用例 main 1项通过（11.635秒）、PR与合成文件复测2项通过（11.619秒）。通知授权保留，density恢复420。
- 因此本次62个不同设备用例累计61通过、1项既有全屏断言失败；复跑不重复计数，首轮失败不抹掉，无host timeout/仪器中断/跳过。未发现本 PR 新增功能回归；真机、真实账号/模型、完整 Ubuntu/Node/DSH、旧 Android 渲染与正式签名未验收，API Key保存仍暂停。
- 流程缺口：PR原样 `check-handoff.py` 会报 Stale handoff；本地测试副本按规则新增本会话交接并刷新后才执行构建，未关闭检查。合并后须同步测试记录、刷新内容指纹；本次没有提交/推送这些文档。完整执行边界见[本次交接](handoffs/20261103-1809-pr-test-plan.md)。
- 后续用户已在 GitHub squash 合并为 `ece5c82`，0.1.2打包轮快进到该提交；其代码树与上述固定PR一致。同步与新包结果另见[当前交接](handoffs/20261103-1906-sync-package-012.md)，不将本节结果冒充新release重跑。

## 产物分类验证（2026-11-03，非产品重测）

- `scripts/test-app-release.py` 12项、`test-agent-workflow.py` 12项、`test-publication.py` 19项均通过；源码分类、原生附件白名单、发布收集、模拟正式签名分类输出和清单相对路径有回归覆盖。两个签名/原生打包 shell 语法检查通过。
- 迁移前记录22个产物文件的SHA-256，分类后逐一确认所有原始内容仍存在，未删除旧APK/源码；当前发布只保留0.1.1，旧0.1.2移入撤回归档。归档清单含16个文件。
- 新增路径回归初始1失败/3错误；实现后通过。首次工作流回归有1项合成fixture缺少新说明文件，补fixture及实际分类输出断言后12项通过，未放宽签名路径保护。
- 本次只改宿主脚本/文档和移动产物，未重编APK、未运行Gradle/JVM/lint/模拟器、未操作正式密钥或重新执行原生源码打包；下述97项JVM与20项设备是前轮结果，不计为本轮新跑。原交接在0.1.2提交前清理，本节保留实际验证与失败，分类和归档校验入口见[取包说明](ARTIFACTS.md)。

## v0.1.1 产品验证：登录、空闲权限与应用更新（2026-11-03）

目标 `com.tanuki.modern.workspacecheck`、`versionName=0.1.1` / `versionCode=3`，开发证书。代码改动先加回归再实现；不是生产证书/真实登录/模型任务的正式验收。

| 范围 | 本轮实际结果 | 限制 |
|---|---|---|
| JVM | **97 项通过，失败/错误/跳过 0** | OAuth 入口/一次票据、空闲/显式撤销、Feed/清单/数字与 URL、字节/文件链接/哈希/APK 身份等；不是设备系统安装链 |
| modern debug 与 AndroidTest 构建 | 成功；明确核对包名、versionName/code、ARM64 和实际开发证书 | `-I scripts/workspace-verification.init.gradle -PtestBuildType=debug -PdevelopmentReleaseSigning=true`，无并行 flavor 构建 |
| debug lint | **0 error，53 warnings，1 information** | 包含 UsableSpace25、RequiresFeature9、GradleDependency7、交互/可访问性等提示；未宣称全部既有或清零 |
| 宿主脚本 | 发布准备 9、工作流12、公开边界回归19，均通过；签名 shell 语法通过 | 合成 badging/证书数据，不读取正式密钥 |
| 复用模拟器 debug | `Pixel_7_API_34_arm64` / `emulator-5572`、ARM64 API34/WebView159，**6 类19 项最终全套通过** | `AgentBrowserControlTest`、`AppBrandingTest`、`ApplicationUpdateNativeTest`、`ApplicationUpdatePresentationTest`、`BrowserIntegrationTest`、`WebAuthenticationTest`；不是前轮14类54项全部重跑 |
| 官方登录合成路径 | 官方平台授权、预开空白异步弹窗/主框架导航、一次性精确 callback、来源/端口/重放拒绝通过 | 未使用真实 DeepSeek 账号；管理 token/Cookie 不复制至网页或日志 |
| 浏览器30秒 | 设备真实等待验证临时接管归还；交互复位/显式撤销不恢复等通过 | 后台本身不撤销；不改变进程级整页绘制设置 |
| 实际点击更新检查 | 原生设置显示「暂无可用于应用内更新的 Release；发布时需附带 app-update.json」 | 公开 Atom/附件，**不调用 GitHub API/不需要 token**；无本版清单时不假装有更新 |
| 原生覆盖文件基线 | 单独 Native 3 项通过，种合成私有文件；发布 APK 覆盖后加 `verifyUpgradeMarker=true` 读回原值并清理合成文件 | 未重新创建冒充保留；不是完整 Linux/真实用户数据覆盖测试 |
| 版本更正前 0.1.2 non-debuggable 包/覆盖后回归 | 一键准备脚本成功，实际 APK 包名/0.1.2/3/SDK26/34/ARM64/开发证书核对；同一 AVD `adb install -r` 后 **6 类20项通过，104.346秒** | 发布目录真实 APK 被测试；没有生产签名/Release 上传，AndroidTest 与 debug 同证书 |

最终用户更正为 v0.1.1 后重新执行 release/AndroidTest 构建、97 项 JVM 与 debug lint，结果仍如上表；`prepare-app-release.py --version 0.1.1 --version-code 3 --channel workspacecheck` 成功。覆盖前重新种合成私有文件，安装最终 0.1.1 APK 后加 `verifyUpgradeMarker=true` 跑同6类，**OK (20 tests)，113.154秒**，无失败/跳过/ADB断线，开发证书不变。发布打包另修正 ZIP 版本根目录与扁平目录原生 SHA 清单各一项，9 项宿主回归通过。

首轮18项中合成 WebView 等待超时1项，及 UI fixture 未等异步回调/小窗遮挡，均保留为失败尝试；补阶段诊断、正确等待/关测试小窗后最终19项通过。原生测试曾引用错误字段/参数次序导致编译失败，修正后完整构建通过。没有把跳过或主机超时计为通过。当前真实账号、真机、完整 Linux 环境覆盖/弱网首次安装、未来更高 Release 系统授权安装链仍待验；API Key 保存继续暂停。

命令、产物和数据边界见[本轮交接](handoffs/20261103-1316-login-app-update.md)、[应用内更新](APP-UPDATES.md)、[发布说明](RELEASE.md)。以下全部是前轮/历史范围，不计为本轮新跑。

## 文档维护验证：提交前清理交接（2026-11-03，历史）

- 仅修改工作规则、发布/接手文档和交接引用，清理 4 份已被替代的过程记录；未修改产品代码。
- 新跑 `python3 scripts/test-publication.py` 19 项、`python3 scripts/test-agent-workflow.py` 12 项，均通过；本地 Markdown 链接检查通过。该检查不自动判定自然语言交接是否过期。
- 没有重跑 JVM、lint、AndroidTest 或设备任务；此前发布咨询已成功构建标准 modern unsigned APK（`com.tanuki.modern`、仅 ARM64、非 debuggable、未签名），不是本次清理的新构建，也不是可直接安装的正式包。
- 保留以下修复轮与前轮的原始验证范围；交接印章/源码包最新校验结果见[当前状态](AGENT-STATE.md)。没有新的 Git 提交/推送、tag 或 Release。

## 产品修复验证：DSH 解析与弱网恢复（2026-11-03）

| 范围 | 实际结果 | 边界 |
|---|---|---|
| 预置官方依赖锁 | 686 个非根记录均为官方 HTTPS/SHA-512，文件摘要与 APK 固定值一致 | 从官方 registry 新生成的公开元数据，不含依赖实现，不等于发布者签名 |
| 产品依赖图脚本宿主验证 | 模拟 Linux/ARM64/glibc、Node 24.22.2/26.13.0/22.23.4，各选中 560 个归档且不改锁；3 个错误来源/入口校验/缺失必需依赖样本被拒绝 | 使用宿主 npm 11.19.0；不是 Android、PRoot、下载或实际安装测试 |
| JVM | 64 项通过，失败/错误/跳过均 0 | 新增锁缓存、默认预置锁、隔离/损坏/符号链接/失败重试与命令到期/外部取消回归；不代表手机性能 |
| Android 编译/组装 | modern debug、开发签名 release、debug AndroidTest 均成功 | 单一 init/flavor 串行 Gradle，完整构建/JVM/双 lint 4m7s；更新声明后再次组装，不安装 |
| lint | debug/release 各 0 错误、49 警告、1 提示 | 非零警告，不能当全量产品验收 |
| APK 身份与签名 | debug/release 均为 `com.tanuki.modern.workspacecheck`、Tanuki、仅 ARM64；debug=true/release=false；AndroidTest target 同目标；apksigner verify 成功 | 继续使用原开发证书，不是正式签名；未覆盖安装 |
| 公开边界与宿主回归 | Git 工作树/索引扫描 0 发现；19 项上传脱敏、12 项交接/保护回归均通过；两 APK 与重建原生对应源码归档各 0 发现 | 规则有范围限制，不扫描用户数据、真实凭据或设备 |
| 源码/迁移 | 258 个参与交接指纹的公开文件；源码 ZIP 261 个文件（含两份原生附件），0 发现；源码/双 APK/原生外部清单全部匹配；独立目录交接/边界/宿主锁/19+12 项回归通过 | 不依赖原工程路径/Git/私有缓存；结果回填后再次打包核对，不计为 Android 设备通过 |

```bash
python3 scripts/test-dsh-lock.py
source scripts/env.sh
export ANDROID_SDK_ROOT="$TANUKI_SDK"
./gradlew --no-daemon -PtestBuildType=debug -PdevelopmentReleaseSigning=true \
  -I scripts/workspace-verification.init.gradle \
  :app:assembleModernDebug :app:assembleModernRelease :app:assembleModernDebugAndroidTest \
  :app:testModernDebugUnitTest :app:lintModernDebug :app:lintModernRelease
python3 scripts/test-publication.py
python3 scripts/test-agent-workflow.py
python3 scripts/check-publication.py --git
```

- 最终 debug APK SHA-256：`f98d770eb3d0ad51b302e0deab8c456a22fd5f1d92beb0371b26c03cf551c0c5`。
- 最终 release APK SHA-256：`360f4b2408ccc31712e72cc291153f6b2150adee2ab1d38112ee562f7e211d77`。
- 开发证书 SHA-256 继续为 `371dc301e7bb4d5cb2840510855f191eeb2f1ce4d058618361419409654cd84f`。
- 初次 JVM 执行 64 项中有 1 项测试断言失败：协程栈恢复可复制 `CancellationException`，不能要求对象引用相同；已改为检查取消消息与只调用一次，随后 64 项全部通过。没有将首次失败计为通过。
- 仪器回归已补「已安装/缓存锁复用、无元数据解析、现有程序不变」断言，但本次**仅编译**。默认版确实可跳过元数据解析已由产品逻辑/宿主依赖图和 JVM 回归核对；手机弱网提速幅度、完整候选安装、超时后重试和覆盖升级尚未设备验证。
- 没有延长原请求/总命令超时、绕过完整性、删除已安装组件或用户数据，也没有安装、签名迁移、发布、提交或推送。源码交接及迁移收尾结果见[本轮记录](handoffs/20261103-1051-dsh-resolution-cache.md)。

## 前一轮构建/迁移记录（2026-11-02，非本轮重跑）

以下保留前轮的实际范围与散列供对照，**旧 APK 散列不能用于本轮修复包**。

| 范围 | 实际结果 | 边界 |
|---|---|---|
| 上传与脱敏宿主回归 | 19项通过 | 工作树/索引、凭据/身份、品牌/路径、归档预算/元数据、Kotlin字符串与必要署名等合成样本 |
| 工程宿主回归 | 12项通过 | 交接/迁移、模拟设备保护、debug/release包名隔离、正式密钥相对路径/符号链接边界；不调用真实ADB/Gradle或密钥 |
| Android编译/组装 | modern debug、release、debug AndroidTest均成功 | 单一Gradle流程，4m7s；没有执行设备instrumentation |
| JVM | 45项通过，失败/错误/跳过均0 | `testModernDebugUnitTest`；不代表Android/guest/模型实测 |
| lint debug及release | 各0错误、49警告、1提示 | 存量警告保留，非零警告，不是全量产品验收 |
| APK | 两包标签Tanuki、目标均`com.tanuki.modern.workspacecheck`、仅ARM64；debug=true、release=false | `aapt dump badging`核验；test target同目标；不安装 |
| 签名/公开边界 | 两包apksigner verify成功，使用同一开发证书；各自递归扫描0发现 | 不是正式证书；自动规则有范围限制 |
| 原生源码 | 对应源码归档生成成功，公开边界/归档扫描0发现 | 固定PRoot提交与talloc SHA256；不等于设备运行通过 |
| 默认release签名 | signingReport：modern/compat的Config/Store均null | 未传开发签名属性，默认unsigned；compat未编译 |
| 源码/迁移 | 251个源码文件（含2个对应源码附件），ZIP递归扫描0发现，外部散列均匹配；独立目录交接/边界及19+12项宿主回归通过 | 不依赖原工程路径/Git/私有缓存；TAR元数据已归一化 |

```bash
python3 scripts/test-agent-workflow.py
python3 scripts/test-publication.py
source scripts/env.sh
./gradlew -PtestBuildType=debug -PdevelopmentReleaseSigning=true \
  -I scripts/workspace-verification.init.gradle \
  :app:assembleModernDebug :app:assembleModernRelease :app:assembleModernDebugAndroidTest \
  :app:testModernDebugUnitTest :app:lintModernDebug :app:lintModernRelease
```

`AppBrandingTest.applicationAndLauncherAreNamedTanuki`检查应用标识、应用类、资源和Launcher标签；本轮仅编译，不在设备执行。release开发签名仅由显式属性启用，默认release留给维护者正式签名；[RELEASE](RELEASE.md)列明方式。

开发证书SHA256：`371dc301e7bb4d5cb2840510855f191eeb2f1ce4d058618361419409654cd84f`。

- debug APK SHA256：`bb9184a657b94f3718353a68c3c68a189639ca2637be3ddc082045584829c7e8`
- release APK SHA256：`df929e811749c10dd0274c4f2485a710d6eccf61a8a20b6a2cc63d18c453ad9c`

Git与源码边界扫描通过，249个Git候选、0索引、0发现；公开归档/迁移范围保留于本节表格，原已完成交接在0.1.2提交前清理。原生依赖官网首次下载HTTP504，使用官方FTP镜像且SHA256完全匹配后重试成功；未放宽TLS/哈希。

发布扫描不覆盖所有自定义秘密、姓名、图片像素或Git历史，第三方必要许可署名保留。原始日志不上传，不以本机缓存作为交接依据。

## 参考范围（不是本轮设备结果）

既有版本曾通过14类54项API34 ARM64/WebView159设备回归，覆盖浏览器权限、多页、整页尾部截图、缩放/小窗、文件/容量、安装/基础工具和rootfs恢复；使用合成页面，不调用模型或真实微信登录。本轮受影响范围未设备重跑，不能将参考数字算作当前APK通过。

原生运行路线曾验证普通UID、non-debuggable环境下的Ubuntu/Node/PTY/HTTP/DNS/HTTPS及官方DSH；不是跨设备、完整模型任务或安全沙箱证明。

## 未测与限制

- 当前APK桌面/通知、生命周期、进程所有权、guest文件映射、Skill与Blob实际交互未设备执行。
- 全屏像素/系统栏/旋转/网页尾部/AI缩放/小窗须设备回归，不能只查DOM。
- 真实模型/微信登录、HyperOS、长期后台、跨WebView/Android、16KB page-size、全部更新/恢复和正式签名未全面验收。
- 快照仍拒绝超出既有白名单的绝对软链接，包括只读工具/下载挂载；未扩展该安全范围或实测相应场景。
- 当前登录跳转仅合成回归已验证，真实账号仍未验收；API Key 保存继续暂停。跳过、超时、失败或 ADB 断开不计通过。

设备测试先确认专用数据、已装运行环境且无apt/dpkg/真实任务，按[上手指南](AGENT-QUICKSTART.md)明确选择设备。本轮不安装、不清数据、不迁移数据。
