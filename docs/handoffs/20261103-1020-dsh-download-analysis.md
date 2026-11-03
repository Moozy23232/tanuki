# 交接：DSH 下载慢原因分析

- Agent/会话标识：主 Agent / dsh-download-analysis
- 时间：2026-11-03 10:20 CST 开始
- 状态：静态调查与文档收尾已完成；具体设备瓶颈待确认，未实施优化
- 前一记录：[精简初版 README](20261102-2308-readme-minimal.md)

## 用户目标与范围

- 用户询问当前代码中为什么 DSH 下载很慢，而 Ubuntu 和 Node.js 很快。
- 本次静态调查下载、依赖解析与安装链路，解释差异；没有用户设备耗时证据，不将候选原因当作已复现的根因，未修改产品代码。
- 不读取凭据、内部备份、设备数据或旧诊断缓存，不连接/安装设备；登录/API Key 排查继续保持用户暂停。

## 当前状态

- 开始时工作树无改动。当前开发验证目标仍为 `com.tanuki.modern.workspacecheck`；本次没有运行产品/设备任务。
- **已核对的实现差异**：
  1. Ubuntu/Node 下载单个已知大小/散列的归档，测速对象就是该归档，见 `app/src/main/kotlin/com/tanuki/runtime/RuntimeComponents.kt:98-124,179-207`。
  2. DSH 先运行 `npm install --package-lock-only --ignore-scripts --save-exact`；registry 固定为 npm 官方，选择镜像只改变归档地址。见 `app/src/main/kotlin/com/tanuki/runtime/NpmPackageDownloads.kt:44,65-68,262`、`app/src/main/kotlin/com/tanuki/runtime/NpmSources.kt:13-19`。guest npm 请求有 60 秒超时、两次重试，见 `app/src/main/kotlin/com/tanuki/runtime/Launcher.kt:185`；这是配置，不是本次测得的等待时间。
  3. DSH 依赖逐包下载，最多四个 worker；采用同一来源顺序，只有下载失败才尝试下个源。见 `app/src/main/kotlin/com/tanuki/runtime/NpmPackageDownloads.kt:183-224`。没有持续低吞吐切源，30 秒 read timeout 是读取停顿超时，不是最低速度限制；传输错误最多三次尝试。见 `app/src/main/kotlin/com/tanuki/download/Downloader.kt:16,79-96,181-182`。
  4. DSH 只对所选版本入口包测速，最多 256 KiB，结果可复用十分钟；它不能代表每个依赖的镜像同步与实际吞吐。见 `app/src/main/kotlin/com/tanuki/runtime/NpmSources.kt:28-31,61-95,132`、`app/src/main/kotlin/com/tanuki/runtime/SourceSpeedTester.kt:23-29,157-179,202-235`。测速批次预算 12 秒，是各组件共有机制，不宜归因为 DSH 独有的长时间卡顿。
  5. 下载后逐包校验，串行写入 npm 缓存，再运行 `npm ci --offline` 和结果检查。见 `app/src/main/kotlin/com/tanuki/runtime/NpmPackageDownloads.kt:119-130,388-399`。这些 guest 命令经过 PRoot，见 `app/src/main/kotlin/com/tanuki/runtime/Launcher.kt:174-185`；归档传输本身由 Android `Downloader.fetchIntegrity` 执行，不是全部经 PRoot 下载。
  6. 保留归档可省去再次传输，但 `installEngine` 会新建候选目录，成功后删除独立 HOME/npm 缓存，下次仍有解析/写缓存/安装工作。见 `app/src/main/kotlin/com/tanuki/runtime/RuntimeManager.kt:421-435`。未知总大小不阻止 GET 下载，见 `app/src/main/kotlin/com/tanuki/runtime/NpmPackageDownloads.kt:78-98`。
- **次要候选开销，未量化**：每个归档独立落盘 sync、重新读取校验；下载每次回调均汇总数组并更新状态，DSH 未像 Ubuntu/Node 的 `RuntimeManager.download` 那样按 100ms 限频。见 `app/src/main/kotlin/com/tanuki/download/Downloader.kt:103,167`、`app/src/main/kotlin/com/tanuki/runtime/NpmDownloadProgress.kt:19-35`、`app/src/main/kotlin/com/tanuki/runtime/NpmPackageDownloads.kt:148-180`、`app/src/main/kotlin/com/tanuki/runtime/RuntimeManager.kt:361-369`。不能凭静态代码认定这是本机主要瓶颈。
- **待验证**：用户实际卡在解析、归档传输、缓存写入还是离线安装；实际版本、包数、来源、网络/存储速度均未知。没有测速或性能改善结论。

## 本次改动

| 文件 | 改动与目的 |
|---|---|
| `docs/COMPONENTS.md` | 补充 DSH 与 Ubuntu/Node 的性能边界及按界面阶段定位方式 |
| `docs/AGENT-STATE.md` | 更新最新交接、调查范围与下一步，去掉随新增交接失效的固定文件数量 |
| 本记录、`docs/handoffs/CURRENT.json` | 记录静态证据与验证限制；收尾刷新公开源指纹 |
| `artifacts/Tanuki-0.1.0-dev-source.zip`、`artifacts/SOURCE-SHA256SUMS` | 文档变化后重新生成，收尾核对外部校验清单；不更新 APK |

## 验证记录

| 实际命令 / 操作 | 环境与结果 | 证据与限制 |
|---|---|---|
| `read` / `rg -n` 检查上述源码及 `docs/COMPONENTS.md`、`docs/SECURITY.md`、`docs/TEST-REPORT.md` | 静态调查完成 | 未运行下载、设备或用户任务，不等于复现或性能测试 |
| 阅读 `DshInstallationRegressionTest`、`SourceSelectionTest`，检索 `NpmDownloadProgressTest` 等回归断言 | 已确认有选源、校验、缓存与进度测试 | 本次未执行；不计通过，不提供持续低速切源的现有验证结论 |
| 只读辅助调查进程 | 未启动成功：Node 可执行文件不存在，`ENOENT` | 没有可用子 Agent 结论；主 Agent 已自行完成选源与测速源码调查，未反复重试 |
| 通过 Python `pathlib` / Markdown 链接正则检查本次三份文档 | 通过：3 份文档、20 个本地链接 | 仅验证本地链接存在，不校验外站或自然语言真实性 |
| `git diff --check`、`git diff --stat`、`git status --short` | 通过：无差异空白错误，变更限于指南/状态/交接/印章 | 新交接为新增文件；产物受既有忽略规则管理，未提交 |
| `python3 scripts/check-handoff.py --record docs/handoffs/20261103-1020-dsh-download-analysis.md` 及无参数检查 | 通过：250 个参与指纹的公开文件 | 本记录结果回填后再刷新检查，以最终内容为准 |
| `python3 scripts/package-source.py`、`(cd artifacts && shasum -a 256 -c SOURCE-SHA256SUMS)` | 通过：253 个文件，含既有原生对应源码附件；源码 ZIP 外部 SHA-256 匹配 | 结果回填后再次打包核对；不表示重跑 APK 或设备验证 |

- 前一轮 debug/release、JVM、lint 与参考设备记录仍以 `docs/TEST-REPORT.md` 为准，本次未重跑。
- 本次未执行构建、JVM、lint、AndroidTest、网络测速；未读取私有原始日志，没有失败的产品测试或已复现的设备故障。

## 未完成与暂停项

- 具体设备的主导耗时阶段未确认：等待用户提供无凭据的界面阶段、来源、已校验包数、字节速度与耗时。
- 性能优化未实施：本次只回答原因，没有授权修改产品或进行设备测量。
- 登录/API Key 问题继续用户暂停。设备验收、真实模型/微信登录和正式签名等既有限制不变。

## 下一步

1. 告知用户实现差异，询问慢时的阶段名称，优先区分「解析 DSH 依赖」「下载 DSH 依赖（已校验 x/y）」「写入已验证的 npm 缓存」「离线安装 DSH 依赖」。
2. 如用户另行要求优化：新建交接；先在获准的专用数据/设备上分阶段计时，再选择低速切源与尾包调度、可信依赖计划/缓存复用或减少缓存写入重复 I/O。并发提升需实测，不能只把四路改大并宣称解决。
3. 修改必须保持官方版本/integrity 信任边界、TLS 与逐包校验，补相应回归；不使用未经可信验证的镜像元数据直接替代官方校验值。

## 约束与风险

- 不以宿主网络代替手机网络，不把所有安装耗时归因于下载带宽，也不把 npm 官方慢当作已测事实。
- PRoot 影响 guest 安装与文件操作；三组件共用原生下载器，不能笼统归因为 DSH 使用了完全不同的网络实现。
- 不跳过哈希、不清用户数据、不改运行中的安装，不改变官方 UI、审批、沙箱或浏览器授权策略。
- 只读辅助进程的宿主环境故障不影响源码结论，但没有提供独立复核；本次没有重跑产品回归，仍须按阶段实际测量。

## 产物与校验

- 仅更新公开说明、交接和源码包；APK、签名、产品代码不变，没有安装、发布、提交或推送。
- 不打包 `.build`、用户数据、凭据、原始日志或辅助进程输出；源码包使用现有明确白名单。
- 已刷新交接印章、源码 ZIP，并核对外部 `artifacts/SOURCE-SHA256SUMS`；本记录回填后再次执行同样的刷新/打包/核对命令。源码 ZIP 自身散列不写回包内，避免循环。
