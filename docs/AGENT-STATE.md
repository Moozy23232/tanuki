# Tanuki 当前状态

先读本页、[当前交接](handoffs/20261104-1632-pr-release-015.md)、[上手指南](AGENT-QUICKSTART.md)及任务专题；安全边界见[SECURITY](SECURITY.md)，验证依据见[TEST-REPORT](TEST-REPORT.md)。

## 当前交付与验证

本轮目标 **0.1.5/code7**，整合[PR #3](https://github.com/leezzou/tanuki/pull/3)固定head c95fa9f，merge-base为最新main a2a5913，ahead6/behind0；保留0.1.4的ClipData上传及AI查看/操作分离。新增真实触摸先发现浮动导航遮挡网页底部按钮，已给浏览器/DSH视口预留导航安全区；全屏及键盘隐藏导航时不保留该区，同一WebView/文档保持。

本轮实际release/AndroidTest、compat debug构建成功，**97 JVM通过；release lint0错误/71警告/1Hint；49项宿主回归通过；57个不同设备用例最终全部通过**。阶段与中间失败见TEST-REPORT；两项旧文字像素检查已适配图标，测试包重编未改变产品APK。实际候选 **10,370,132字节**、SHA-256 `a21a8ae304c08857b1881f17668f442cab461d14c3c283208ecd5e4aa12dbdf2`。覆盖前旧0.1.4合成私有标记读回并清理，未重种。PR作者API36/22项与前轮0.1.4/44项均独立列历史，不混算。

先完成全部验证、交接/过期记录清理及公开检查，再按本轮授权一次普通push main；推送成功后用上述已测字节verified封装0.1.5六附件。实际Git提交/远端与产物散列由最终回执、`RELEASE-SHA256SUMS`和`app-update.json`核对，不为了回填提交自身再制造后续提交。旧0.1.4完整附件保持原样。

## 环境与数据边界

- 当前目标包com.tanuki.modern.workspacecheck；同开发证书，min26/target34、compileSdk36。本轮已测0.1.5/7的non-debuggable候选，正式密钥未使用。
- 本机复用 **Pixel_7_API_34_arm64 / emulator-5572**，ARM64/API34/WebView159，专用合成数据；无node/proot/apt/dpkg/真实任务。PR作者的Tanuki_UI_API36/emulator-5554仅为其历史验证，不是本机目标。
- 不卸载/清数据/恢复快照，不恢复用户已卸载的旧.modern；不同构建共用输出路径，安装前核验实际包名、签名、versionCode与instrumentation target。
- 合法当前工具连接无操作权也可看普通当前页/截图；操作仍需授权、读取不切页、OAuth临时页不给AI读写或列出。临时接管30秒归还操作权，显式收回持续有效，后台不自行撤销。
- 保持官方DSH UI/模型/Skills与TLS/哈希/审批；PRoot不是恶意代码沙箱。API Key保存继续暂停，不调查/导出真实凭据。

## 未验与已知问题

真实账号/模型、MIUI真机、低API渲染、长期后台/大量复杂标签压力，完整Ubuntu/Node/DSH跨APK覆盖、弱网设备缓存路径及进程死亡后的安装授权期限恢复仍待验。既有浏览器退出全屏systemBars断言未修复，不可算通过；正式签名/迁移和发布后的实际更新安装链未全面验收。

## 产物与交接

本轮先测试和文档/过期交接清理，一次commit/push后用verified-apk与已测SHA封装artifacts/releases/0.1.5六附件；不自动创建tag/上传Release。源码准备区跟随当前工作树，不改写旧release；发布入口见[取包说明](ARTIFACTS.md)和[发布流程](RELEASE.md)。PR作者1535冲突过程已完成，独有验证事实补入TEST-REPORT作者历史节后删除；当前记录保留清理依据。

另一个会话的DSH文件管理运行限制记录20261103-1937-files-runtime-question.md此前尚未落盘，仅保留待确认提示，不作为本轮测试依据。
