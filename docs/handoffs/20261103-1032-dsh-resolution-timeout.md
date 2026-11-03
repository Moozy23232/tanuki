# 交接：DSH 解析超时与覆盖更新保留边界

- Agent/会话标识：主 Agent / dsh-resolution-timeout
- 时间：2026-11-03 10:32 CST 开始
- 状态：静态核对与文档收尾完成；未实施产品优化，设备行为待验证
- 前一记录：[DSH 下载慢原因分析](20261103-1020-dsh-download-analysis.md)

## 用户目标与范围

- 用户先补充最慢的是「解析 DSH 依赖」，且网速慢时会超时；调查期间又询问「安装更新会保留已经安装的 Ubuntu、Node.js 和 DSH 吗」。
- 本次核对解析的两层超时、缓存生命周期，以及 APK 覆盖更新的数据保留边界；优先回答追加的安装更新问题。
- 用户未明确要求修改产品或操作设备；本次只静态定位与说明，不自行切换元数据来源、改超时配置或运行安装。
- 登录/API Key 排查继续用户暂停，不读取私有日志、凭据或备份。

## 当前状态

- 开始时工作树保留上一轮的指南、状态、交接与印章改动；没有回退或改写历史记录。
- 当前开发目标仍为 `com.tanuki.modern.workspacecheck`；本次未启动设备、网络、构建或安装任务，未核实用户设备当前是否空闲。
- **已核对：解析超时**：`NpmPackageDownloads.install` 强制使用官方 npm registry，解析命令为 `npm install --package-lock-only --ignore-scripts --save-exact`，每条安装子命令的 `timeoutMs=1200000`（20 分钟），见 `app/src/main/kotlin/com/tanuki/runtime/NpmPackageDownloads.kt:37-68`。guest 环境设 `npm_config_fetch_timeout=60000`、`npm_config_fetch_retries=2`，重试间隔下限 2 秒/上限 10 秒，见 `app/src/main/kotlin/com/tanuki/runtime/Launcher.kt:185`；整体由 `withTimeout(timeoutMs)` 限制并在退出时终止仍存活的子进程，见 `app/src/main/kotlin/com/tanuki/runtime/Launcher.kt:246-248`。没有具体报错和计时，不能认定用户碰到的是哪一层上限。
- **已核对：缓存**：失败重试保留隔离安装 HOME/npm 元数据缓存及公开归档，但每次重新准备候选安装目录；成功后删除临时安装数据，见 `app/src/main/kotlin/com/tanuki/runtime/RuntimeManager.kt:421-435`。不能声称每次完全没有缓存，也不能保证缓存免联网。官方依赖解析慢不能由更换归档镜像直接解决。
- **已核对：覆盖更新**：`Paths.base=context.filesDir`，Ubuntu/Node 在 `runtime/ubuntu`、`runtime/node`，DSH 在 `engines/<版本>`，配置/会话在 `userdata`、项目在 `workspaces`，见 `app/src/main/kotlin/com/tanuki/runtime/State.kt:41-65`。App `onCreate` 创建 `RuntimeManager`，启动时读取 `.base-verified`、`.installed` 和 `installed.json` 识别已有组件，没有因 APK 版本改变自动调用初始化或重装，见 `app/src/main/kotlin/com/tanuki/TanukiApp.kt:40-47`、`app/src/main/kotlin/com/tanuki/runtime/RuntimeManager.kt:65-98`。正常同包同签名覆盖升级不删除私有目录；包名/签名不匹配不是同一覆盖安装，不能用卸载绕过后还期待数据自动保留。
- **已核对：未完成安装不算保留成功组件**：DSH 完成安装和验证后才写 `.installed` 并提交到 `engines/<版本>`；已有标记且依赖锁一致时 `installEngine` 直接复用，见 `app/src/main/kotlin/com/tanuki/runtime/RuntimeManager.kt:410-435`。仅卡在解析的候选不算成功安装；APK 更新不会自动补完这一阶段，Ubuntu/Node 的现有文件仍可保留。
- **待验证**：用户具体超时层级、弱网耗时，以及目标设备的实际覆盖升级；本次没有复现或性能改善结论。

## 本次改动

| 文件 | 改动与目的 |
|---|---|
| `docs/COMPONENTS.md` | 补充解析命令 20 分钟上限、APK 覆盖更新与未完成候选的保留边界 |
| `docs/AGENT-STATE.md` | 更新最新交接、用户确认的慢阶段、只读核对结果与待办 |
| 本记录、`docs/handoffs/CURRENT.json` | 记录用户追加问题、证据和未测范围；收尾刷新公开文件指纹 |
| `artifacts/Tanuki-0.1.0-dev-source.zip`、`artifacts/SOURCE-SHA256SUMS` | 文档变化后重新生成并核对；APK 不更新 |

## 验证记录

| 实际命令 / 操作 | 环境与结果 | 证据与限制 |
|---|---|---|
| 接手阅读状态、上一交接、上手指南、README、SECURITY、AGENTS、模板、TEST-REPORT；`git status --short` | 完成 | 上轮工作树改动仍在；不当作本次重新构建/回归 |
| `read` / `rg -n` 核对上述源码、构建配置、组件指南 | 静态核对完成 | 未发起 npm 请求或连接设备，不证明实际升级结果 |
| 检索猜测的 `app/src/androidTest/kotlin/com/tanuki/ComponentManagementTest.kt` | 路径不存在，`rg` 返回 2 | 其他目标仍有结果；不计产品测试失败或通过，未据此宣称存在升级回归 |
| Python `pathlib` / Markdown 正则检查三份本次文档的本地相对链接；`git diff --check` | 通过：23 个本地链接；无空白错误 | 不验证外站或自然语言真实性 |
| `python3 scripts/check-handoff.py --record docs/handoffs/20261103-1032-dsh-resolution-timeout.md` 及无参数检查 | 通过：251 个参与指纹的公开文件 | 本记录结果回填后再次刷新并校验 |
| `python3 scripts/check-publication.py --git` | 通过：252 个公开候选、250 个索引项、0 发现 | 未操作索引/提交；自动规则不保证覆盖全部秘密或 Git 历史 |
| `python3 scripts/package-source.py`、`python3 scripts/check-publication.py --archive artifacts/Tanuki-0.1.0-dev-source.zip`、`(cd artifacts && shasum -a 256 -c SOURCE-SHA256SUMS)` | 通过：254 个归档文件，含既有原生对应源码附件；递归扫描 0 发现；外部 SHA-256 匹配 | 结果回填后再次打包核对；APK 不变 |
| `git diff --stat`、`git status --short` | 变更限于指南/状态/印章，另有两份本会话新增交接 | 上一交接改动保留；未进行提交或推送 |

- 前一轮 Android 构建、45 项 JVM、lint 和历史设备范围仍以 `docs/TEST-REPORT.md` 为准，本次未执行。
- 本次无产品测试失败；没有运行构建、JVM、lint、仪器测试、网络测速或 APK 覆盖升级，不能声称已修复弱网安装。

## 未完成与暂停项

- DSH 解析阶段性能优化未实施。若继续，需要明确修改范围；可考虑可信依赖锁复用、元数据缓存和更清楚的网络失败提示，不能仅提高下载并发或扩大超时即宣称解决。
- 无用户的超时文案/耗时，单请求失败与整体超时暂未区分；设备数据/升级测试未授权。
- 登录/API Key 继续用户暂停，真实模型任务、正式签名等既有未测范围不变。

## 下一步

1. 回答用户：同包同签名的 APK 覆盖更新会保留成功安装的 Ubuntu/Node/DSH 及私有项目/配置；不要先卸载或清数据，更新前先结束安装/真实任务并停止服务。
2. 提醒卡在解析且未安装成功的 DSH 仍需完成安装；App 更新不等于成功安装组件，也不意味着解析慢已修复。
3. 如用户继续要求优化解析：建立新交接，确认错误类型与耗时，保持官方元数据/integrity 信任边界，先补合适回归后改代码；测量/安装须明确设备与专用数据。

## 约束与风险

- 未执行设备覆盖升级；只能说明 Android 正常覆盖安装与当前代码的数据保留机制，不对损坏安装、跨包名/跨签名或卸载后的恢复作保证。
- 不换回旧 `.modern`；debug/release 使用相同输出路径，后续交付要检查实际包名/签名/target，不能靠文件名判断。
- 不关闭 TLS/integrity，不清数据、不卸载，不对正在 apt/dpkg、安装或真实任务的 App 覆盖安装。
- 不重复启动前次已知宿主 Node 路径缺失的辅助进程，本次由主 Agent 核查，未新增委派失败。

## 产物与校验

- 产品代码与 APK 无本次改动；仅更新公开文档、交接、指纹和源码包。
- 没有安装、签名、提交、推送或发布；不打包 `.build`、用户数据、凭据、原始日志或辅助进程输出。
- 已刷新交接印章、重新生成源码 ZIP 并核对外部 `artifacts/SOURCE-SHA256SUMS`；本记录回填后再次执行刷新、打包、扫描和散列核对。源码 ZIP 自身散列不写回包内，避免循环。
