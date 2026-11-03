# Agent 上手指南

目标：只拿到源码 ZIP、不访问此前聊天或 `.build`，也能定位代码、构建、选择正确的测试入口，并继续维护交接。

先读根目录 [AGENTS.md](../AGENTS.md) 和 [当前状态](AGENT-STATE.md)。每任 Agent 都要按 [交接模板](handoffs/TEMPLATE.md) 留下新记录；完整流程在 AGENTS.md，不是可选事项。

## 1. 项目是什么

Tanuki（命名空间为 `com.tanuki`）是 Kotlin/Jetpack Compose Android 客户端：APK 原生 supervisor/PRoot 启动私有 Ubuntu ARM64，再运行 Node 和官方 DSH。官方 Web UI 在管理 WebView 中显示；独立浏览器有自己的 profile、工具连接和系统悬浮窗。不是 Web 包壳外接 Mac 服务，也不是 Via 内核。

数据与程序分开：`runtime`、`engines`、`userdata`、`workspaces`，模拟硬链接内容另存 `link-store`。升级/卸载组件和卸载 App 不是一回事。不要用清数据或旧快照“修复”用户问题。

## 2. 新机器前置条件

已验证宿主是 Apple Silicon macOS。当前 `runtime/build-native-runtime.sh` 固定 Darwin NDK 工具链目录，`prepare-build-tools.sh` 下载 macOS ARM64 JDK；**不承诺 Linux/Windows 无修改可用**。Intel Mac 使用已有合适 JDK17并验证 NDK；其他系统须先移植宿主工具链路径，不能将 Android ARM64 运行验证当作宿主跨平台验证。

需要：

- 完整 JDK17，含 `jlink`；Gradle8.9 Wrapper、AGP8.7.3、Kotlin1.9.24由项目配置。
- Android SDK：platform-tools、platforms/android-34、build-tools/34.0.0、cmdline-tools/latest；AGP可能按需补充其匹配的 Build Tools。
- NDK `27.2.12479018`、Git、GNU make、patch、curl、Python3。原生脚本不需要 CMake。
- 首次构建允许访问 Gradle/Maven、固定 GitHub PRoot提交与官方 talloc 来源；有哈希/提交校验。打包的原生对应源码不等于自动离线构建，缓存齐全后才尝试 `--offline`。
- 设备测试需要 Android ARM64；当前完整回归环境是 API34、WebView159。x86 Android模拟器不能运行本项目的ARM64 Ubuntu链；管理页至少Chromium123+并通过实际CSS能力检测。

不要照搬前任绝对路径，也不要重装全局 SDK/Java 或清空 AVD。已有工具优先复用。

```bash
cd <解压后的工程目录>
export ANDROID_SDK_ROOT="<现有 SDK 路径>"
export TANUKI_JDK="<完整 JDK17 的 home>"  # Mac通常是 .../Contents/Home
printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > local.properties

# 没有完整JDK、且宿主是Apple Silicon Mac，才用项目下载器：
# unset TANUKI_JDK
# bash scripts/prepare-build-tools.sh
# 它不会覆盖已有local.properties；复用工程时要自行确认SDK路径。
```

`local.properties`、`.build` 和 JDK缓存不进入源码包。设备/WebView、SDK不是随源码一起分发的“现成环境”。

## 3. 第一次构建（不操作设备）

无需JDK/SDK的宿主流程自测：`python3 scripts/test-agent-workflow.py`、发布清单/版本回归 `python3 scripts/test-app-release.py`；上传过滤/脱敏回归为 `python3 scripts/test-publication.py`，发布前按 [上传检查](PUBLICATION.md) 检查候选文件。它只在临时目录使用模拟ADB/Gradle，验证交接过期检测、默认不操作设备、包名/target保护和失败/跳过/中断判定，不代表Android产品测试。

```bash
python3 scripts/check-handoff.py
bash scripts/verify-workspace.sh
```

默认只执行独立 `.workspacecheck` 的 app/test APK构建、JVM测试和lint，不安装、不启动、不读取设备数据。脚本对当前工程路径动态定位，无个人绝对路径、固定序列号或私有日志依赖。

对应Gradle命令（源码改动前先遵守交接检查）：

```bash
source scripts/env.sh
./gradlew -PtestBuildType=debug -I scripts/workspace-verification.init.gradle \
  :app:assembleModernDebug :app:assembleModernDebugAndroidTest \
  :app:testModernDebugUnitTest :app:lintModernDebug
```

输出：

- App：`app/build/outputs/apk/modern/debug/app-modern-debug.apk`
- Tests：`app/build/outputs/apk/androidTest/modern/debug/app-modern-debug-androidTest.apk`
- JVM报告：`app/build/test-results/testModernDebugUnitTest/`
- lint：`app/build/reports/lint-results-modernDebug.html`

**包名不能从输出文件名推断。**上面的init脚本生成 `com.tanuki.modern.workspacecheck`；不传init脚本则是旧标准 `.modern`，但输出路径相同。默认 `androidTest` 目标为probe，debug测试必须有 `-PtestBuildType=debug`。不要并行跑两种构建。

`scripts/build-debug.sh`、`scripts/install-and-launch.sh` 是标准包历史入口，后者硬编码启动 `.modern`；不要用它给当前用户保留的 `.workspacecheck` 安装/启动。非debuggable probe与正式release测试也不是此回归脚本的范围。

## 4. 设备验证（模拟器已获许可，真机另行确认）

用户已允许用模拟器测试代码，**每台开发机优先复用同一个现有测试 ARM64 AVD，不得每任 Agent 都新建**。先列出现有 AVD、确认数据可测且无安装/真实任务；本开发机沿用 `Pixel_7_API_34_arm64`。记录启动后的明确 serial，不必反复询问是否可启动；只有没有可用测试 AVD 时才新建。不得 wipe、恢复旧快照、清空已有安装或覆盖未确认数据的 AVD。真机与真实账号仍需另外授权；模拟器结果不等于真机验收。首次专用 AVD 未装运行环境时，可先运行不依赖 Ubuntu/Node/DSH 的合成 WebView/浏览器/更新安全测试，环境相关用例仍需满足下列前置条件。

先查看设备并确认是允许测试的数据集：

```bash
source scripts/env.sh
"$TANUKI_ADB" devices -l
```

在专用测试安装中先通过App四步安装 Ubuntu → Node → 基础工具 → DSH；停止DSH并确认没有apt/dpkg/下载/真实任务运行。测试可能改变该安装的环境包、设置、浏览器授权和合成文件，**不能对真实账号/项目的数据直接运行**。首次没有环境时，依赖环境的测试可能跳过；跳过不是完整通过。

```bash
bash scripts/verify-workspace.sh \
  --run-device-tests --confirm-test-data --device <明确选定的 ARM64 序列号>
```

脚本先检查交接、构建、用SDK `apkanalyzer`核验app/test包名和instrumentation target，再 `install -r` 两个APK并执行固定14类回归。不卸载、不 `pm clear`、不重置系统、不自动切换DSH权限。设备参数和确认缺一就拒绝；只传设备而未开启设备测试也会拒绝。

当前14类回归上次为54项（具体条目随开发可变化），约5分钟，调用工具建议预留至少900秒，环境首次补包还需网络时间。脚本不会给测试强加短超时。ADB返回0不代表JUnit成功，脚本检查最终OK、逐项完成以及失败/跳过/中断；本地完整日志在 `.build/logs/workspace-regression-*.txt`，不直接公开。

它不是所有Android测试类的自动全选器：组件替换、冷安装、真实guest工具、更新矩阵、真机和模型等测试另有前置条件。执行前读对应测试与 [TEST-REPORT](TEST-REPORT.md)，不能把本脚本通过等同于全部功能验收。

## 5. 模块地图

| 责任 | 主要文件（相对工程根） |
|---|---|
| App/页面编排 | `app/src/main/kotlin/com/tanuki/TanukiApp.kt`、`MainActivity.kt`、`ui/TanukiUi.kt` |
| 安装状态/操作互斥/恢复 | `app/src/main/kotlin/com/tanuki/runtime/RuntimeManager.kt`、`State.kt`、`RuntimeComponents.kt` |
| Ubuntu工具 | `app/src/main/kotlin/com/tanuki/runtime/RuntimeRequirements.kt`（PROFILE变更需要使旧缓存失效） |
| 子进程/日志 | `app/src/main/kotlin/com/tanuki/runtime/Launcher.kt`（含EventLog）、`RuntimeService.kt` |
| 下载/选源/进度 | `app/src/main/kotlin/com/tanuki/download/Downloader.kt`、`runtime/SourceSpeedTester.kt`、`runtime/OperationProgress.kt` |
| Node/npm/DSH依赖 | `app/src/main/kotlin/com/tanuki/runtime/NpmPackageDownloads.kt`、`NpmDownloadProgress.kt` |
| DSH网页/认证/紧凑/导出 | `app/src/main/kotlin/com/tanuki/web/DshWebView.kt`、`DshViewport.kt`、`OriginPolicy.kt`、`BlobDownloads.kt` |
| 独立浏览器/工具RPC | `app/src/main/kotlin/com/tanuki/browser/AgentBrowserController.kt`、`BrowserIntegration.kt`、`BrowserLibrary.kt` |
| 系统悬浮窗/后台 | `app/src/main/kotlin/com/tanuki/browser/BrowserService.kt`、`BrowserOverlayWindow.kt`、`BrowserOverlayGeometry.kt` |
| 文件/软预算/模拟链接 | `app/src/main/kotlin/com/tanuki/storage/WorkspaceFileManager.kt`、`WorkspaceStorage.kt`、`L2sStore.kt` |
| 快照/安全解包/DSH 更新 | `app/src/main/kotlin/com/tanuki/storage/DataSnapshots.kt`、`SafeTar.kt`、`update/UpdateManager.kt` |
| Tanuki APK 更新/版本打包 | `app/src/main/kotlin/com/tanuki/update/ApplicationUpdateManager.kt`、`AppReleaseManifest.kt`、`AppUpdatePolicy.kt`、`scripts/prepare-app-release.py`；见 [应用内更新](APP-UPDATES.md) / [发布](RELEASE.md) |
| Android原生启动链 | `app/src/main/cpp/supervisor.c`、`runtime/build-native-runtime.sh`、`runtime/patches/proot-exitkill.patch` |
| AI浏览器客户端/Skill | `app/src/main/assets/browser/tanuki-browser.mjs`、`app/src/main/assets/browser/SKILL.md` |
| 测试 | `app/src/test/kotlin/com/tanuki/`、`app/src/androidTest/kotlin/com/tanuki/` |

表中同一单元内省略目录的文件均与该单元第一个文件同目录。改动前核对实际定义，尤其不要误找不存在的 `runtime/EventLog.kt` 或把管理WebView与独立浏览器混为一谈。

## 6. 常见陷阱与决策

- `AtomicFile` 首写在 `.new`，不能只对base路径chmod；连接发布已用输出fd `fchmod`。
- PRoot模拟硬链接必须保留持久 `link-store`，不能把新写的用户数据藏进随后删除的staging。
- `RuntimeManager.deleteTree` 已处理自有rootfs的000目录恢复；不能把修复扩成遍历/修改外部符号链接目标。
- 浏览器默认授权、用户收回的规则来自用户最后选择；连接capability不等于已经证明的DSH模型/会话身份。
- `pauseTimers()`是进程级；会影响DSH/后台AI。整图flag也是进程级且无disable；不能“截图前开、截图后关”。不要退回 `draw(Canvas)` 裁小窗冒充整页。
- `workspace-write` 无可用bwrap/Landlock时拒绝shell是官方策略，不是Android缺“文件权限”；不替用户改 `danger-full-access`。
- 子资源重定向/私网拦截不是完整SSRF隔离；现有安全边界详见 [SECURITY](SECURITY.md)。

## 7. 完成与交付

1. 完成本次交接记录并更新 `AGENT-STATE.md`，诚实写明实际验证及未测范围。每次 Git 提交/推送前按 [上传检查](PUBLICATION.md) 先归档必要结论、删除已过期交接并修复引用；当前记录、模板和仍需使用的验证/未完成记录保留。
2. `python3 scripts/check-handoff.py --record docs/handoffs/<本次记录>.md`，再不带参数检查。
3. `python3 scripts/check-publication.py --git` 检查实际Git候选；`python3 scripts/package-source.py` 生成 `artifacts/sources/Tanuki-0.1.1-source.zip`，再检查ZIP内容、嵌套附件与 `artifacts/SOURCE-SHA256SUMS`。
4. APK有变化才更新其交付副本/校验并核对包名、签名和安装目标。仅文档/宿主脚本变化，不声称业务APK已重编译或设备测试全套重跑。
5. 产物分类见 [取包说明](ARTIFACTS.md)：有效发布只在 `artifacts/releases/<版本>/`，源码准备区为 `artifacts/sources/`，旧包归入 `artifacts/archive/`，不要在根目录散放旧 APK/ZIP。
6. 最终答复给清楚的产物路径、完成项、暂停/阻塞项和下一步，不留下只存在聊天里的必要信息。
