# Tanuki 当前状态

先读本页、[当前交接](handoffs/20261104-1911-fullscreen-insets.md)、[上手指南](AGENT-QUICKSTART.md)及任务专题。安全边界见[SECURITY](SECURITY.md)，历史测试/发布事实集中在[TEST-REPORT](TEST-REPORT.md)。

## 当前任务

- `fix/browser-floating-navigation` 基于main/4509972，源码0.1.5/code7。浏览器默认透显已恢复，可在「浏览器设置→通用」主动开启并保存「避让网页底部控件」。顶部原生工具与DSH输入区保护不变。
- 已测双debug/AndroidTest、97 JVM、12项API36专项；modern lint0错误/71警告/1提示。避让项补帧同步后另重跑通过，不累计次数。修复测试APK在artifacts/builds/browser-floating-fix。
- 悬浮修复已本地提交cbe9e9c；全屏重复占位已独立修复，窗口布局模式保持一致，不再退出时重复避让。本轮双debug/AndroidTest、97 JVM、15项API36专项通过，lint0/71/1。两笔提交已完成，用户追加授权普通推送本功能分支，结果以远端回执为准；中心人/AI按钮未实施。

## 环境与边界

- 本机复用Tanuki_UI_API36/emulator-5554，ARM64/API36/WebView133；独立验证包com.tanuki.modern.uipolish.workspacecheck。正常workspacecheck安装不覆盖，不清数据/卸载/恢复快照；安装前核验身份及无真实任务。
- 上游0.1.5发布采用另一开发机API34、开发签名non-debuggable包，97/57/49等证据见测试现状，不算本轮结果。正式发布目录与main/旧分支均不改。
- AI合法连接可读普通当前页，操作需授权；读取不切页，OAuth页不给AI读写/列出。临时接管30秒归还，显式收回持续有效；后台不自行撤销。
- 保留官方DSH与TLS/哈希/审批；PRoot不是恶意代码沙箱。**API Key保存继续暂停**，不读/导出真实凭据。

## 未验与产物

- 真机/低API/真实模型、长期后台/大量标签、完整环境升级、弱网设备缓存和安装授权期限进程死亡恢复未本轮验收。旧systemBars断言已在API36诊断为缺少caption导致组合可见性误判，并修正为真实状态/导航栏与初始状态复核；低API/真机仍待复验，历史结果不回写为通过。
- 当前源码候选在artifacts/sources/Tanuki-0.1.5-source.zip。最新独立debug包在artifacts/builds/browser-fullscreen-fix，包含两项修复，可覆盖此前独立测试包，不覆盖正常安装；正常Studio输出恢复但未安装。
- 已完成的发布过程交接删去，必要验证、失败和未完成范围保留在测试现状/专题。未来发布仍先确认版本，按[发布流程](RELEASE.md)执行，不自动建tag/Release。
