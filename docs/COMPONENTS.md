# 组件安装与管理

首次打开应用，按概览中的四个步骤准备环境。每一步显示当前状态、可选版本和下一步操作；选择版本只保存偏好，点击安装才会更换组件。

1. **Ubuntu**：选择 ARM64 Ubuntu Base 镜像，保留默认的「自动择优」下载源即可。应用自动测速、下载、校验并准备基础系统。
2. **Node.js**：选择版本并安装。应用检查 DSH 声明的 Node 兼容范围，并实际启动候选程序。需要先完成 Ubuntu。
3. **基础工具**：点击「检查并补齐」，执行21项检查，覆盖Git/SSH客户端、curl、Python SSL/venv、CA、xz、xdg-user-dirs、OpenSSL、ripgrep、jq、ZIP、rsync、file、less、patch、make、pkg-config等。仅补缺失/损坏组件，不安装SSH服务端或桌面。已有工具通过时直接进入下一步，Ubuntu/Node安装也会准备其必需项。
4. **DSH**：从官方版本列表选择并安装，默认自动测速并选取归档源。完成依赖安装和隔离启动检查后打开官方页面，在页面配置模型、工作区和命令权限。

组件已经就绪后，概览以运行状态和启动操作为主。重装、卸载、缓存整理等维护入口仍可使用；操作前需要停止 DSH。

## 可选版本

| 组件 | 选项 |
|---|---|
| Ubuntu Base ARM64 | 24.04.5（默认）、26.04.1、22.04.5、24.04.4、24.04.3、22.04.4、26.04 |
| Node.js ARM64 | 24.22.2（默认）、26.13.0、24.10.0、22.23.4、26.12.1、26.8.2、26.0.0、25.9.0、24.22.0、24.21.0、24.18.1、24.15.0、24.11.1 |
| DSH | 从 registry.npmjs.org 获取官方版本列表；初始默认 0.2.1-rc.5 |

固定 Ubuntu / Node 清单包含官方归档长度与 SHA-256。目录中列出版本不代表所有 Ubuntu × Node × DSH 组合均已在设备实测；不兼容的候选不能覆盖当前程序。

## 自动测速与选择下载源

Ubuntu 与 Node 各提供八个下载源，DSH 归档提供五个源：

| 组件 | 下载源 |
|---|---|
| Ubuntu | 官方、华为云、华为云镜像、清华 TUNA、中科大 USTC、阿里云、南京大学 NJU、北外 BFSU |
| Node.js | 官方、npmmirror、清华 TUNA、华为云、华为云镜像、腾讯云、阿里云、南京大学 NJU |
| DSH 归档 | npm 官方、npmmirror、华为云、华为云镜像、腾讯云 |

默认自动模式下，进入安装步骤就开始测速。每个源最多采样 256 KiB，以首字节延迟和传输耗时比较，选择当次最快的可用源；安装前可复用十分钟内的结果。切换版本会清除旧版本的结果。安装失败时按候选顺序尝试其他源，取消和磁盘空间不足会立即停止。

界面直接显示正在测速、已选择的源和测得速度。「查看测速与下载源」用于查看各源结果、重新测速或手动选择。手动选择会保留，后续测速不会覆盖；选择「自动择优」可恢复自动模式。部分镜像未同步某个版本时，失败结果保持可见。

所有源必须通过相同的官方 SHA-256 校验。短时间采样用于本次选择，无法保证该源在之后的网络条件下始终最快。

## 进度与取消

- Ubuntu、Node、pnpm：显示真实字节百分比、已下载量、总大小和传输速度。完整缓存先通过校验，再显示已复用。
- DSH依赖：优先复用已验证的依赖锁，默认版可使用 APK 预置官方锁；过滤当前平台后下载，不强制逐包大小预探测。没有可复用锁时才解析官方元数据，显示实际响应次数与耗时，不编造百分比。汇总真实下载字节，归档通过SHA-512后写入缓存，再进行离线安装阶段。
- 基础工具：下载与配置阶段显示 apt 自身报告的百分比。
- 校验、解包、依赖解析、安装检查分别显示阶段名称；它们没有可信总量时不编造百分比。下载达到 100% 表示数据已下载，整个安装仍需完成后续检查。

取消后保留可安全复用的公开下载。重试时验证续传条件、大小和哈希；没有有效续传条件的归档重新下载。

## DSH 依赖解析与弱网重试

- 默认 `0.2.1-rc.5` 随 APK 提供来自官方 npm 的完整依赖锁，内容由 APK 中固定的 SHA-256 绑定；不必在手机上逐个联网解析全部依赖。**包归档仍需下载**，不等于完整离线安装。
- 其他版本优先复用持久依赖锁或同版本已安装程序中带有效 `.installed` 标记的锁。缓存绑定 DSH 版本、选定发布的 SHA-512、实际 Node/npm 版本和解析策略；命中后仍检查入口、必需依赖、平台、引擎与所有选中归档的完整性。
- 缺少可信锁才运行官方 npm 解析，使用 `--prefer-offline` 优先采用已取得的公开元数据。完整依赖图检查通过后立即原子保存锁到 `filesDir/downloads/npm-locks`；后面的下载或候选安装失败不会使下一次重试丢失这份解析结果。失败解析时原有隔离 npm 缓存也不清除。
- 请求仍有 60 秒超时与两次重试，整命令仍以 20 分钟为上限；没有仅靠延长超时冒充提速。命令自身到期现在作为失败报告，不再混同「用户取消」，用户主动取消及外层任务取消仍保持取消语义。
- 预置锁不是发布者签名；信任边界见 [SECURITY](SECURITY.md)。本次已实现并完成宿主/构建验证，手机弱网耗时与实际安装仍待专用设备测试，不承诺特定倍数。

预置锁位于 [`app/src/main/assets/dsh-locks/0.2.1-rc.5.json`](../app/src/main/assets/dsh-locks/0.2.1-rc.5.json)，含 686 个非根包记录及跨平台可选依赖。2026-11-03 使用隔离 HOME/空 npm 配置、宿主 Node 26.13.0/npm 11.19.0 从 `https://registry.npmjs.org` 生成：在全新目录写入无脚本的 `tanuki-dsh-runtime@1.0.0`、`private:true` 与精确的 `@deepseek-ai/dsh:0.2.1-rc.5` 依赖，再执行 `npm install --package-lock-only --ignore-scripts --save-exact --engine-strict --no-audit --no-fund --registry=https://registry.npmjs.org --os=linux --cpu=arm64 --libc=glibc --fetch-timeout=60000 --fetch-retries=2`。更新锁时必须重新核对官方入口 SHA-512、全部官方 HTTPS 归档/SHA-512、平台与引擎，并同步 `BundledDshLock` 的版本/入口校验/全文件 SHA-256；不可只改摘要绕过审核。

```bash
# 只做宿主模拟依赖图检查，不下载/安装、不代表 Android 实测。
python3 scripts/test-dsh-lock.py
```

实现见 [`NpmDependencyLocks.kt`](../app/src/main/kotlin/com/tanuki/runtime/NpmDependencyLocks.kt)、[`NpmPackageDownloads.kt`](../app/src/main/kotlin/com/tanuki/runtime/NpmPackageDownloads.kt)、[`CommandDeadline.kt`](../app/src/main/kotlin/com/tanuki/runtime/CommandDeadline.kt)。

## 为什么 DSH 安装可能比 Ubuntu / Node 慢

两类组件的下载工作量不同，不能只按总字节比较：

- **Ubuntu / Node** 下载一个已知大小和 SHA-256 的归档，测速对象就是该归档。**DSH** 先准备或复用依赖锁，再逐包下载；当前归档下载最多四路并发，多个请求的首字节等待和尾部慢包都会影响总耗时。见 [`RuntimeComponents.kt`](../app/src/main/kotlin/com/tanuki/runtime/RuntimeComponents.kt)、[`NpmPackageDownloads.kt`](../app/src/main/kotlin/com/tanuki/runtime/NpmPackageDownloads.kt)。
- **无可信锁时，镜像没有覆盖依赖解析**：需要运行 `npm install --package-lock-only` 时，registry 仍固定为 `https://registry.npmjs.org`。选择 npmmirror 等只改变归档地址；官方元数据链路慢时，换归档源不能消除这部分等待。npm 请求设置为 60 秒超时、两次重试；解析命令另有 20 分钟的整体上限。弱网可能先遇到单次请求失败，也可能因累积等待达到命令上限，具体需结合报错与耗时区分，不能仅靠延长总超时判断已经解决。见 [`NpmPackageDownloads.kt`](../app/src/main/kotlin/com/tanuki/runtime/NpmPackageDownloads.kt)、[`Launcher.kt`](../app/src/main/kotlin/com/tanuki/runtime/Launcher.kt)。
- **短采样不代表全部依赖**：DSH 对所选版本的入口包最多采样 256 KiB，而非对整套依赖测速；结果可复用十分钟。正式下载出错才回退，没有按持续低吞吐自动切源；30 秒读取超时也不等于最低速度要求。见 [`NpmSources.kt`](../app/src/main/kotlin/com/tanuki/runtime/NpmSources.kt)、[`SourceSpeedTester.kt`](../app/src/main/kotlin/com/tanuki/runtime/SourceSpeedTester.kt)、[`Downloader.kt`](../app/src/main/kotlin/com/tanuki/download/Downloader.kt)。
- **下载结束不等于安装结束**：归档逐包校验后，还会在 PRoot 中串行写入 npm 缓存，再运行 `npm ci --offline` 解包安装和检查。这些是本地 I/O 与安装耗时，不是下载带宽；保留归档可以省去再次传输，但目前仍需重新构建候选安装。见 [`NpmPackageDownloads.kt`](../app/src/main/kotlin/com/tanuki/runtime/NpmPackageDownloads.kt)、[`RuntimeManager.kt`](../app/src/main/kotlin/com/tanuki/runtime/RuntimeManager.kt)。

上述是实现中的性能边界，不是特定手机的测速结论。定位时先记录界面的阶段、来源、已校验包数、字节/速度和阶段耗时，区分「解析 DSH 依赖」「下载 DSH 依赖」「写入已验证的 npm 缓存」「离线安装 DSH 依赖」。未知总大小本身不会阻止下载，不能据此判断卡死。优化仍须保留官方校验信息、TLS 与逐包完整性检查；不能为了提速跳过校验。

## 重装、卸载与数据

**覆盖更新 Android App 不等于重装运行环境。** 同包名、同签名且系统允许升级的 APK 覆盖安装会保留应用私有数据；Ubuntu、Node、DSH 分别位于 `filesDir/runtime/ubuntu`、`filesDir/runtime/node`、`filesDir/engines/<版本>`，启动时读取安装标记和元数据，不会仅因 APK 更新就自动下载默认组件。项目、配置与会话也保存在应用私有目录。见 [`State.kt`](../app/src/main/kotlin/com/tanuki/runtime/State.kt)、[`RuntimeManager.kt`](../app/src/main/kotlin/com/tanuki/runtime/RuntimeManager.kt)、[`TanukiApp.kt`](../app/src/main/kotlin/com/tanuki/TanukiApp.kt)。

更新前先结束下载、apt/dpkg 和真实任务，再停止 DSH；不要先卸载 App 或清除应用数据。当前开发目标是 `com.tanuki.modern.workspacecheck`，标准 `.modern` 是另一安装目标，不能混用。签名不匹配时不能通过卸载旧 App 来绕过覆盖安装限制并期待数据自动保留。尚在「解析 DSH 依赖」且未提交成功的候选不是已安装的 DSH，覆盖更新 App 不会自动补完它；已安装的 Ubuntu/Node 不需要因此重装。以上是实现与 Android 数据保留边界，本次没有执行设备覆盖升级测试。

Ubuntu、Node、DSH 程序和项目、用户数据分别保存。卸载组件需要确认，保留用户主目录、DSH 配置、会话和项目；卸载整个 Android 应用或清除应用数据则会删除这些私有文件。

Ubuntu、Node 更换版本先准备候选，提交前失败时保留旧组件，中断由事务记录恢复。更换 Ubuntu 会移除原系统内自行 apt 安装的额外软件；必需工具会重新检测和补齐。

DSH 同版本重装先在独立目录安装和检查候选程序，通过后才替换；失败、取消或切换中断会保留或恢复旧程序，项目与会话不随程序回退。备用安装只准备另一个程序版本，不切换当前数据。跨版本切换先执行候选检查、保存匹配快照，再启动新版本；回退数据需要匹配的 Ubuntu、Node 和 DSH 程序。

DSH 的版本、依赖锁和 SHA-512 来自官方 npm；镜像只提供匹配校验值的归档。某个依赖未同步时只回退该包。安装后保留经过校验的依赖归档和已验证锁，便于重试和重装；「清理下载缓存」可移除它们，不删除已安装程序或 APK 预置锁。缓存并不保证完整离线安装：归档不足、版本列表更新或没有可信锁的版本仍可能需要联网。安装空间检查使用保守估算，包脚本可能需要更多空间。

## 文件与命令权限

PRoot 共享 Android 内核和 App UID，显示的 root 不是设备 Root。应用实际检测 bwrap 与 Landlock，并显示命令沙箱能力。没有可用后端时，官方 `workspace-write` 会拒绝执行 shell；安装指引会解释这一状态。

应用不自动修改 DSH 权限策略。需要运行 shell 时，用户可在官方 DSH 中明确选择 `danger-full-access`，其含义是放开该运行环境内的命令限制，仍受 Android 应用权限约束。

## 主题、全屏与项目容量

原生界面和 WebView 使用跟随系统的浅色、深色主题。DSH 页面保留同一个 WebView，全屏只改变显示区域；悬浮退出入口和系统返回键可退出全屏。若用户在官方 DSH 中手动选择了主题，应在其设置中恢复跟随系统。

项目容量可在「设置 → 项目空间 → 调整容量预算」中修改，详见 [项目空间说明](WORKSPACE-STORAGE.md)。
