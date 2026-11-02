# 测试现状

Tanuki验证目标：`com.tanuki.modern.workspacecheck`，命名空间 `com.tanuki`，ARM64、minSdk26/targetSdk34。debug/release构建与证书、宿主与设备执行必须分别记录，源码中有测试不等于已运行。

## 本轮验证

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

Git与源码边界扫描通过，249个Git候选、0索引、0发现；公开归档/迁移核验见[当前交接](handoffs/20261102-2226-tanuki-builds.md)。原生依赖官网首次下载HTTP504，使用官方FTP镜像且SHA256完全匹配后重试成功；未放宽TLS/哈希。

发布扫描不覆盖所有自定义秘密、姓名、图片像素或Git历史，第三方必要许可署名保留。原始日志不上传，不以本机缓存作为交接依据。

## 参考范围（不是本轮设备结果）

既有版本曾通过14类54项API34 ARM64/WebView159设备回归，覆盖浏览器权限、多页、整页尾部截图、缩放/小窗、文件/容量、安装/基础工具和rootfs恢复；使用合成页面，不调用模型或真实微信登录。本轮受影响范围未设备重跑，不能将参考数字算作当前APK通过。

原生运行路线曾验证普通UID、non-debuggable环境下的Ubuntu/Node/PTY/HTTP/DNS/HTTPS及官方DSH；不是跨设备、完整模型任务或安全沙箱证明。

## 未测与限制

- 当前APK桌面/通知、生命周期、进程所有权、guest文件映射、Skill与Blob实际交互未设备执行。
- 全屏像素/系统栏/旋转/网页尾部/AI缩放/小窗须设备回归，不能只查DOM。
- 真实模型/微信登录、HyperOS、长期后台、跨WebView/Android、16KB page-size、全部更新/恢复和正式签名未全面验收。
- 快照仍拒绝超出既有白名单的绝对软链接，包括只读工具/下载挂载；未扩展该安全范围或实测相应场景。
- 登录/API Key不列为已修复；跳过、超时、失败或ADB断开不计通过。

设备测试先确认专用数据、已装运行环境且无apt/dpkg/真实任务，按[上手指南](AGENT-QUICKSTART.md)明确选择设备。本轮不安装、不清数据、不迁移数据。
