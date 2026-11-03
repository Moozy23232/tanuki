# 当前状态

接手先读 [AGENTS](../AGENTS.md)、[上手指南](AGENT-QUICKSTART.md) 和 [测试现状](TEST-REPORT.md)。源码是实现事实，不依赖聊天或本机缓存。

## 最新交接

[Git 前清理过期交接](handoffs/20261103-1154-handoff-retention.md)（已完成，交接/源码包检查通过）。已在项目规则、上传流程、上手指南和模板固化提交/推送前清理；必要结论已归入当前状态/专题文档，未按日期删除仍有效依据。本次仅文档维护，新跑 19+12 项宿主回归及本地 Markdown 链接检查均通过；JVM/lint/设备未重跑。此前修复及交接提交 `adf9d55` / `b1ef5e3` 已推送 `origin/main`，本次改动/删除尚未暂存或提交，不自动推送或发布。发布步骤见[发布说明](RELEASE.md)，正式密钥、版本/tag 与发布范围仍待用户决定。

仍需保留的记录：[构建验证依据](handoffs/20261102-2226-tanuki-builds.md)、[DSH 修复与设备待测项](handoffs/20261103-1051-dsh-resolution-cache.md)、[发布咨询与未决事项](handoffs/20261103-1158-release-publishing.md)。

## 产品与安装边界

- 产品：Tanuki `0.1.0-dev`；命名空间 `com.tanuki`，应用类 `TanukiApp`，主题 `Theme.Tanuki`。
- 开发验证目标：`com.tanuki.modern.workspacecheck`；标准modern为 `com.tanuki.modern`，使用同一输出路径，构建后必须核验包名/target，不凭文件名安装。
- 已生成的开发 APK：`artifacts/Tanuki-0.1.0-dev-workspacecheck-debug.apk`及同目录`Tanuki-0.1.0-dev-workspacecheck-release.apk`；同目标包/ARM64/开发证书，debug=true、release=false，包名/标签/证书/公开边界已核验。正式发布证书由用户保管。
- 保留叶狸图案、浏览器全屏、新页Windows PC模式和官方DSH UI/模型/插件/Skills。
- 不确认用户数据或真实任务前不覆盖安装、清数据或启动instrumentation；不自动迁移包名或证书不同的安装。

## 验证与工程边界

DSH 修复轮结果见[TEST-REPORT](TEST-REPORT.md)：JVM 64 项通过；debug/release/AndroidTest 组装成功；双 lint 各 0 错误/49 警告/1 提示；19+12 项宿主回归通过，Git/双 APK/原生源码公开边界 0 发现。官方锁 686 个记录校验通过，宿主模拟三个 Node 版本各选中 560 个归档，非 Android 安装/测速。AndroidTest 仅编译，设备、真实任务与正式签名未验收。修复轮源码/归档/外部散列及独立目录检查已通过，历史数量不作当前文件数。

此前发布咨询的标准 release 输出为 `com.tanuki.modern` 的 unsigned APK，不能凭同一路径当作可安装验证包；`artifacts/` 中已核验的 workspacecheck 双 APK 及原生附件不变。本次文档与清理后已重打源码包（260 条目/257 文件指纹），交接、公开边界、独立解压与源码/双 APK/原生外部散列检查通过；详情见本次交接。

工程仅保留必要源码/测试/构建入口/许可及公共文档；此前构建/下载/生成JNI缓存与日志已清理。产物、日志、配置、凭据、JDK、含数据的AVD和并发暂存被Git排除；源码ZIP仅另含2个明确的原生对应源码附件。上传前检查实际暂存内容与公开归档，忽略规则不清理历史，自动扫描不是绝无隐私的证明。

## 暂停与外部限制

- 登录和API Key问题不在本轮范围，不继续调查或宣称修复。
- Android设备、真实模型/微信登录、HyperOS、长期后台、全部更新/恢复及正式签名尚未本轮验收。
- PRoot不是恶意代码沙箱；浏览器按用户选择授权，不绕过官方审批或静默改 `danger-full-access`。

## 下一步

1. 如需实际验收：明确专用设备与数据、确认没有安装/apt/dpkg/真实任务，再核对当前已装包名/证书和待安装 APK 的 target，获准后覆盖安装；不卸载/清数据。验证默认锁无需解析、失败重试复用、其他版本官方回退与真实弱网耗时，不能把宿主模拟当手机通过。
2. 后续开发先建立新交接；每次 Git 提交/推送前清理已归档的过期交接、修复引用并明确暂存删除，随后刷新记录/状态/印章和源码 ZIP，不沿用旧散列。
3. 设备验收须用户明确指定专用设备/数据且无安装或真实任务；不把Android编译当设备通过。
4. 上传前设置公开昵称/隐私邮箱，运行 [上传检查](PUBLICATION.md) 并复查实际暂存；不自动提交或推送。
