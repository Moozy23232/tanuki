# 交接：DSH 依赖解析提速与弱网重试

- Agent/会话标识：主 Agent / dsh-resolution-cache
- 时间：2026-11-03 10:51 CST 开始
- 状态：实现、构建、宿主验证及交接/源码包收尾已完成；手机弱网效果待验证
- 前一记录：[DSH 解析超时与覆盖更新保留边界](20261103-1032-dsh-resolution-timeout.md)

## 用户目标与范围

- 用户明确要求修改 DSH 下载慢的问题；此前已确认最慢在「解析 DSH 依赖」，弱网会超时。
- 本次针对依赖锁复用、减少重复联网解析与弱网失败恢复修改产品代码，先补回归源码，再实现并执行验证。
- 不改官方 DSH UI/模型/权限，不关闭 TLS 或完整性校验，不读取凭据/会话，不自动安装到设备。
- 登录/API Key 仍由用户暂停。覆盖 APK 更新是否保留环境的问题已在前一记录说明，不以本次构建代替覆盖升级测试。

## 当前状态

- 开始时只有静态调查，没有产品优化；保留上轮文档/交接/印章，不回退或改写历史。
- **已实现**：默认 DSH `0.2.1-rc.5` 的官方完整锁随 APK 提供，并由固定 SHA-256 绑定；重试/重装优先复用绑定 DSH integrity、实际 Node/npm 和解析策略的持久锁或有效同版本安装锁。命中后仍重新检查完整依赖图、平台、引擎和完整性。
- **已实现**：缺少可信锁才向官方 npm 解析，`--prefer-offline` 优先采用已有公开元数据，显示真实响应次数/耗时而不是假百分比；完整检查通过后、归档下载前原子保存锁，后续失败不再丢弃解析成果。
- **已实现**：命令自身到期作为 `IOException` 类失败，不再被顶层当作用户取消；主动/外层任务取消仍传播。请求 60 秒/两次重试与整命令 20 分钟上限未延长。下载阶段仍四路并发/逐包校验，没有加入未经实测的高并发或慢源调度。
- **已验证**：64 项 JVM、modern 双 APK/AndroidTest 组装、双 lint、预置锁宿主依赖图和公开边界；详见下表及 `docs/TEST-REPORT.md`。
- **待验证**：手机弱网耗时、实际候选 npm 安装/缓存写入、超时后的用户重试、覆盖 APK 升级和不同 Node/npm 组合的真实运行。没有性能倍数或设备通过结论。
- 当前验证目标仍为 `com.tanuki.modern.workspacecheck`，本次未连接、安装或启动设备任务。

## 本次改动

| 文件 | 改动与目的 |
|---|---|
| `app/src/main/kotlin/com/tanuki/runtime/NpmDependencyLocks.kt` | 环境绑定的依赖锁身份、16MiB 上限/摘要/原子提交/符号链接拒绝、可信锁选择与先检查后保存、预置锁摘要 |
| `app/src/main/assets/dsh-locks/0.2.1-rc.5.json` | 官方 HTTPS 生成的完整 lock v3，686 个非根记录，保留跨平台可选依赖；不是只含当前平台的裁切清单 |
| `app/src/main/kotlin/com/tanuki/runtime/NpmPackageDownloads.kt` | 实际 Node/npm 探测、持久/已安装/预置锁优先；生成无脚本入口清单，缺省才联网解析；真实响应进度与超时错误提示；镜像测速推迟到依赖检查后 |
| `app/src/main/kotlin/com/tanuki/runtime/CommandDeadline.kt`、`Launcher.kt` | 区分命令自身超时与外层/主动取消；保留 Launcher 的子进程 finally 清理 |
| `app/src/test/kotlin/com/tanuki/NpmDependencyLocksTest.kt`、`CommandDeadlineTest.kt` | 新增 19 项 JVM 回归：锁绑定/损坏/重定向/重试/不保存失败结果/预置摘要与版本隔离、命令到期和取消语义 |
| `app/src/androidTest/kotlin/com/tanuki/NpmInstallWithoutSizeProbeTest.kt` | 增加现有锁复用、无元数据解析和现有程序不变断言；本次只编译未执行 |
| `scripts/test-dsh-lock.py` | 宿主执行原产品依赖图脚本，只替换目标平台/Node 声明与 npm 库路径；验证官方锁与三个拒绝样本，不下载或安装 |
| `docs/COMPONENTS.md`、`docs/SECURITY.md`、`THIRD_PARTY_NOTICES` | 更新缓存/弱网边界、锁的生成重建方法、预置锁来源和非发布者签名说明；README 不涉及该实现，保留其精简范围 |
| `docs/TEST-REPORT.md`、`docs/AGENT-STATE.md`、本记录与 `CURRENT.json` | 区分本轮与前轮验证，记录未设备测试、刷新交接与公开指纹 |
| `artifacts/` 明确交付副本/外部清单 | 新双 APK、重建原生对应源码和最终源码包；不提交 Git，不包含私有日志或用户数据 |

## 验证记录

| 实际命令 / 操作 | 环境与结果 | 证据与限制 |
|---|---|---|
| 接手阅读 AGENTS、状态、上次交接、上手指南、模板、README、SECURITY 和相关源码；`git status --short` | 已完成 | 保留既有工作树改动，不操作用户环境 |
| 隔离 HOME/空 npm 配置执行官方 `npm install --package-lock-only --ignore-scripts --save-exact --no-audit --no-fund --engine-strict --registry=https://registry.npmjs.org --os=linux --cpu=arm64 --libc=glibc --fetch-timeout=60000 --fetch-retries=2` | 成功生成公开锁；Node 26.13.0/npm 11.19.0；686 个非根记录、全部官方 HTTPS/SHA-512 | 只写独立 `.build` 暂存，不修改已安装 npm 包，不代表用户手机网络速度；公开重建步骤在组件指南 |
| `python3 scripts/test-dsh-lock.py` | 通过：文件摘要匹配；模拟 Linux/ARM64/glibc 与三个 Node 版本，各选中 560 个归档且不改锁；错误入口 SRI、非官方 URL、缺失必需依赖三样本均拒绝 | 使用宿主 npm 11.19.0，不是 Android、PRoot、完整安装或测速 |
| 首次 `:app:testModernDebugUnitTest` | 64 项中 1 项断言失败 | `CommandDeadlineTest` 原断言要求取消对象引用相同，但协程栈恢复会复制异常；改为检查取消消息与仅执行一次，没有更改生产取消逻辑或吞掉取消 |
| `source scripts/env.sh; export ANDROID_SDK_ROOT="$TANUKI_SDK"; ./gradlew --no-daemon -PtestBuildType=debug -PdevelopmentReleaseSigning=true -I scripts/workspace-verification.init.gradle :app:assembleModernDebug :app:assembleModernRelease :app:assembleModernDebugAndroidTest :app:testModernDebugUnitTest :app:lintModernDebug :app:lintModernRelease` | 成功，4m7s；JVM 64 项、失败/错误/跳过均 0；双 lint 各 0 错误/49 警告/1 提示 | 同一 init/flavor 串行；AndroidTest 仅编译，测试报告来自 XML，不将编译算设备通过 |
| 更新第三方声明后再次运行上述同参数的三项 `assembleModernDebug` / `assembleModernRelease` / `assembleModernDebugAndroidTest` | 成功；最终 APK 包含最新声明和预置锁 | 产品 Kotlin 及 JVM 结果未变化；不安装 |
| `aapt dump badging`、`apksigner verify --print-certs`、`apkanalyzer manifest print` | 双包 Tanuki、目标 `com.tanuki.modern.workspacecheck`、仅 ARM64、min26/target34；debug=true/release=false；instrumentation target 同包；原开发证书一致 | 公共证书摘要见产物段；不是正式发布证书 |
| `python3 scripts/test-publication.py`、`python3 scripts/test-agent-workflow.py` | 分别 19、12 项通过 | 合成数据，不调用真实设备/密钥；不清数据 |
| `python3 scripts/check-publication.py --git` | 工作树/索引 0 发现 | 自动规则有范围限制，不修改暂存或读取被忽略凭据；收尾再扫最终文档 |
| `bash scripts/package-native-sources.sh` 及 `check-publication.py --archive` 分别检查两 APK/原生源码包 | 成功；三份归档各 0 发现 | 固定 PRoot 提交/talloc 摘要未变，重新收录当前必要许可/构建来源；未把私有 `.build` 直接打包 |
| `git diff --check` | 通过 | 收尾再核对状态/实际暂存，未经授权不提交或推送 |
| `python3 scripts/check-handoff.py --record docs/handoffs/20261103-1051-dsh-resolution-cache.md` 及无参数检查 | 通过：258 个参与指纹的公开文件 | 本次结果回填后再次刷新检查，与最终记录一致 |
| `python3 scripts/package-source.py`、源码归档扫描/外部清单、独立目录交接与 19+12 项回归/宿主锁检查 | 通过：261 个源文件（含两份原生附件）、0 发现、全部清单匹配；独立目录交接/边界/宿主锁与 19+12 项通过 | 结果回填后再次打包和独立核对；不重编完整 Android，不计设备通过 |

## 未完成与暂停项

- 手机弱网性能、完整候选 DSH 安装、同版本修复/其他版本更新、覆盖 APK 后保留环境尚未本轮设备测试。新增仪器断言不能计为通过。
- 没有改变动态版本必须从官方取得可信元数据的边界；无缓存的非默认版本仍受首次解析网络影响。缺少归档时仍须下载，缓存不等于完整离线安装。
- 归档慢但不断流时的动态切源/尾包调度、串行 npm 缓存写入优化不在本次实现范围，不宣称解决所有阶段。
- 登录/API Key 继续用户暂停；真实模型/微信、HyperOS、长期后台和正式签名等既有限制保留。

## 下一步

1. 本次源码/交接已完成；使用明确交付路径和外部清单，只交付已核验的 APK，不自动覆盖安装。
2. 获准后先确认同包同证书、专用数据且没有 apt/dpkg/安装/真实任务，再做覆盖安装；禁止卸载/`pm clear`。默认版应显示「使用 APK 预置 DSH 依赖锁」或「复用…依赖锁」，不再显示整树元数据解析。
3. 分别验证中断归档后的同环境重试、清理下载缓存后的预置锁恢复、切换实际 Node/npm 后重新检查，以及无预置/无安装锁的其他版本官方回退；记录真实阶段与耗时，不承诺提速倍数。

## 约束与风险

- 预置锁固定一次完整的官方依赖图；更新时必须从官方 HTTPS 重建并审核入口/全部归档/SHA-512，连同 APK 摘要和回归更新。不能用镜像提供的任意 integrity 代替官方信任来源。
- 缓存摘要检测损坏、身份隔离和原子提交一致性，不是发布者签名或同 UID 恶意代码沙箱；命中后仍运行产品完整依赖检查。
- 保留官方 UI、模型、插件、Skills、TLS、审批和浏览器授权；不恢复旧 `.modern`、不清用户数据、不切 `danger-full-access`。
- 本轮没有再次派发已知宿主 Node 路径故障的辅助进程；源码调查/实现/复核由主 Agent 完成，无缺失的独立复核结果冒充证据。

## 产物与校验

- 新 debug：`artifacts/Tanuki-0.1.0-dev-workspacecheck-debug.apk`，SHA-256 `f98d770eb3d0ad51b302e0deab8c456a22fd5f1d92beb0371b26c03cf551c0c5`。
- 新 release：`artifacts/Tanuki-0.1.0-dev-workspacecheck-release.apk`，SHA-256 `360f4b2408ccc31712e72cc291153f6b2150adee2ab1d38112ee562f7e211d77`。
- 两包均为原开发证书：SHA-256 `371dc301e7bb4d5cb2840510855f191eeb2f1ce4d058618361419409654cd84f`；只可与相同包名/证书的既有安装覆盖更新，不作跨包名/签名保留承诺。
- `WORKSPACECHECK-SHA256SUMS`、`RELEASE-SHA256SUMS` 保存双包清单；原生源码清单按现有脚本在工程根核对，源码 ZIP 清单在 artifacts 内核对。源码 ZIP 自身散列不回写包内，避免循环。
- 已重打 `artifacts/Tanuki-0.1.0-dev-source.zip`，核对 `SOURCE-SHA256SUMS` 并检查迁移副本；本次结果回填后再次执行同样的刷新/打包/独立核对。不上传 `.build`、配置、凭据、原始日志、用户数据或辅助进程输出。
- 没有设备安装、卸载、清数据、正式签名、发布、Git 提交/推送；所有产物仅在本机明确目录。
