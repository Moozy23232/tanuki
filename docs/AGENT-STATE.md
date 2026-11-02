# 当前状态

接手先读 [AGENTS](../AGENTS.md)、[上手指南](AGENT-QUICKSTART.md) 和 [测试现状](TEST-REPORT.md)。源码是实现事实，不依赖聊天或本机缓存。

## 最新交接

[精简初版 README](handoffs/20261102-2308-readme-minimal.md)（已完成）。首页仅保留三句介绍与使用/许可入口；链接、交接和发布边界检查通过，源码ZIP已同步更新。仅改文档和必要交接；产品与构建结果仍以[前一记录](handoffs/20261102-2226-tanuki-builds.md)为准，本次不重跑、不操作设备。

## 产品与安装边界

- 产品：Tanuki `0.1.0-dev`；命名空间 `com.tanuki`，应用类 `TanukiApp`，主题 `Theme.Tanuki`。
- 开发验证目标：`com.tanuki.modern.workspacecheck`；标准modern为 `com.tanuki.modern`，使用同一输出路径，构建后必须核验包名/target，不凭文件名安装。
- 本轮交付：`artifacts/Tanuki-0.1.0-dev-workspacecheck-debug.apk`及同目录`Tanuki-0.1.0-dev-workspacecheck-release.apk`；同目标包/ARM64/开发证书，debug=true、release=false，包名/标签/证书/公开边界已核验。正式发布证书由用户保管。
- 保留叶狸图案、浏览器全屏、新页Windows PC模式和官方DSH UI/模型/插件/Skills。
- 不确认用户数据或真实任务前不覆盖安装、清数据或启动instrumentation；不自动迁移包名或证书不同的安装。

## 验证与工程边界

前一轮构建/回归结果见[TEST-REPORT](TEST-REPORT.md)。双lint各0错误/49警告/1提示；AndroidTest仅编译，设备、真实任务与正式签名未验收。源码ZIP的交接/边界和独立目录19+12项回归通过，含完整原生对应源码；外部SHA清单核对通过。

工程仅保留必要源码/测试/构建入口/许可及16份文档，250个Git源码候选；此前构建/下载/生成JNI缓存与日志已清理。产物、日志、配置、凭据、JDK、含数据的AVD和并发暂存被Git排除；源码ZIP仅另含2个明确的原生对应源码附件。上传前检查实际暂存内容与公开归档，忽略规则不清理历史，自动扫描不是绝无隐私的证明。

## 暂停与外部限制

- 登录和API Key问题不在本轮范围，不继续调查或宣称修复。
- Android设备、真实模型/微信登录、HyperOS、长期后台、全部更新/恢复及正式签名尚未本轮验收。
- PRoot不是恶意代码沙箱；浏览器按用户选择授权，不绕过官方审批或静默改 `danger-full-access`。

## 下一步

1. 后续开发先建立新交接，源码变化后刷新记录/状态/印章和源码ZIP，不沿用旧散列。
2. 设备验收须用户明确指定专用设备/数据且无安装或真实任务；不把Android编译当设备通过。
3. 上传前设置公开昵称/隐私邮箱，运行 [上传检查](PUBLICATION.md) 并复查实际暂存；不自动提交或推送。
