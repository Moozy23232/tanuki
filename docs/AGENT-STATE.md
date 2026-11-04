# Tanuki 当前状态

先读本页、[当前交接](handoffs/20261104-1535-pr3-conflicts.md)、[上手指南](AGENT-QUICKSTART.md)和任务相关专题；安全边界见[SECURITY](SECURITY.md)，历史验证集中在[TEST-REPORT](TEST-REPORT.md)。

## 当前交付

- `feat/browser-layout` 已解决 PR #3 与 main/a2a5913 的冲突，保留已发布三笔功能提交，通过合并更新，不强推；按用户授权合并提交/普通推送，结果以远端回执为准，不修改main或旧功能分支。
- 当前源码版本 **0.1.4 / code6**。保留上游ClipData上传、AI查看/操作分离及发布工具；浏览器紧凑布局、首焦全选、各页浮动导航、标签图标/角标和取消12页限制均保留。
- 本轮新跑：modern/compat debug、modern AndroidTest、**97 JVM、22项API36专项全部通过**；modern lint **0错误/71警告/1提示**。不是上游44项release验证重跑，也不是全产品验收。
- 按用户要求收拢过程文档：已完成交接的必要结果归入测试现状，仅保留当前记录、模板和印章。`licenses` 与最新main一致，不把主线许可资料算成本PR新增；不删除必要许可/署名。

## 环境与数据边界

- 本机根目录作业，不新建worktree。复用 `Tanuki_UI_API36` / `emulator-5554`，ARM64/API36/WebView133；其他历史记录的API34设备不属于本机当前目标。
- 本轮仅覆盖独立验证包 `com.tanuki.modern.uipolish.workspacecheck`，0.1.4/6 debug；正常开发/上游发布目标 `com.tanuki.modern.workspacecheck` 不覆盖。标准 `.modern` 是不同包名，用户卸载的旧安装不恢复。
- 不清数据、不卸载、不恢复快照；有安装或真实任务时不覆盖安装/运行仪器测试。保持官方DSH UI/模型/Skills、TLS/哈希/审批；PRoot不是恶意代码沙箱。
- AI合法当前连接可看普通当前页，操作仍需授权；读取不切页，OAuth临时页不给AI读写或列出。临时接管空闲30秒归还，显式撤销操作权持续有效，后台不自行撤销。

## 暂停与未验收

- **API Key保存继续由用户暂停**，不调查或导出真实凭据。
- 真实账号/模型、MIUI真机、低API渲染、长期后台与大量标签内存压力未本轮验证。
- 完整Ubuntu/Node/DSH会话跨APK覆盖、弱网首次安装与锁缓存设备路径、进程死亡后的安装授权期限恢复仍待验；具体历史边界保留在测试现状。
- 既有浏览器退出全屏 `systemBars()` 断言未修复，不计入通过数；正式密钥/签名迁移及发布后更新安装全链不视为已验收。

## 产物与下一步

- 本轮仅刷新 `artifacts/sources/Tanuki-0.1.4-source.zip` 和外部SHA，测试APK/日志留忽略目录；正常Studio输出恢复但不安装。
- 上游0.1.4开发签名release及六附件事实见测试现状与[取包说明](ARTIFACTS.md)；本轮不重编/替换正式发布目录、不建tag/Release。
- 普通推送本功能分支后等待用户审阅；未来发布仍先确认版本，按[发布流程](RELEASE.md)执行。
