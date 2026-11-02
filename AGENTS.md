# Tanuki — Agent 工作规则

此文件是每任 Agent 的接手入口，适用于整个工程。不要依赖此前聊天、`.build` 缓存或前任 Agent 的记忆。

## 1. 每次接手先读

1. [当前状态](docs/AGENT-STATE.md)：当前交付、暂停项、下一步及最新交接记录。
2. 当前状态指向的 `docs/handoffs/` 交接记录。
3. [上手指南](docs/AGENT-QUICKSTART.md)：环境、构建、验证、模块地图。
4. [README](README.md) 和任务相关文档。安全/运行改动先读 [SECURITY](docs/SECURITY.md)；测试结论对照 [TEST-REPORT](docs/TEST-REPORT.md)。

历史报告、截图和旧交接已按用户上传前精简要求清理，必要实现决策归入专题文档，验证摘要保留于 `docs/TEST-REPORT.md`。历史测试数字/包名不能覆盖当前状态。源码是实现事实，验证记录是“已测”依据；冲突必须查证，不凭旧摘要改代码。

## 2. 每任 Agent 必须交接（包括未完成、受阻或只读任务）

**开始时**创建本次唯一记录 `docs/handoffs/YYYYMMDD-HHMM-topic.md`，从 [模板](docs/handoffs/TEMPLATE.md) 复制，写明本次用户目标、范围和开始状态；更新 `docs/AGENT-STATE.md` 的最新记录链接。不要只写“继续上次”。

**结束、移交或预计中断前**必须：

- 补完本次记录：改动文件与目的、实际验证命令及结果、未测试范围、失败和原因、关键决策、未完成/暂停项、下一步可执行动作、数据与产物边界。
- 更新 `docs/AGENT-STATE.md` 的当前状态。明确区分：已实现、已验证、待验证、用户暂停、外部阻塞。不能把前任测试当成本次新跑的测试。
- 更新受影响的 README/专题文档。结论必须能在源码/公共证据中定位，不能仅指向未打包的 `.build`。
- 写完后运行：
  ```bash
  python3 scripts/check-handoff.py --record docs/handoffs/<本次记录>.md
  python3 scripts/check-handoff.py
  ```
- 交付源码前再运行 `python3 scripts/package-source.py`，核对 `artifacts/SOURCE-SHA256SUMS`。修改文档也会改变源码包，不能继续发旧 ZIP。

只读权限的 Agent 不得违反权限去写文件：输出同模板的完整交接文本，明确“未落盘”，交由主 Agent 保存。并发子 Agent 返回结论和证据，由主 Agent 汇总；不要多人同时覆盖当前状态或印章。下一任 Agent 建立新记录，保留旧记录，不无理由改写历史。

交接检查使用公开源文件内容指纹，不依赖 Git、mtime 或本机路径。源码变化但交接未更新会失败，`verify-workspace.sh` 会先执行此检查。它不能证明自然语言内容真实，仍必须如实记录验证和未完成项。

## 3. 数据、安装和权限硬约束

- 不读取/输出真实 API Key、Cookie、启动 token、用户会话或内部备份。公共证据只用合成数据；原始诊断日志默认放 `.build`，不可直接打包。上传前按 `docs/PUBLICATION.md` 检查 Git 候选和实际暂存内容，保留必要许可署名，不用忽略规则冒充已脱敏。
- 不为排障执行 `pm clear`、卸载用户 App、wipe AVD、恢复旧数据快照或终止其他应用/所有 Node 进程。需要破坏性动作时先取得明确许可。
- 当前目标从 `docs/AGENT-STATE.md` 获取；不要恢复用户已卸载的旧 `.modern`。不同构建共用 APK 输出路径，安装前核验 package 和 instrumentation target，不能凭文件名判断。
- 不对正在安装环境、执行 apt/dpkg 或运行真实任务的应用覆盖安装/启动 instrumentation。设备必须明确选择，数据必须确认可用于测试。
- API Key 保存问题目前由用户暂停；未被新要求恢复前，不自行调查、修改或宣称修复。
- 不自动切换 DSH 的 `danger-full-access`，不关闭审批/TLS/哈希校验，不把 PRoot 宣传成恶意代码沙箱。
- 浏览器授权以当前用户选择为准：默认授权工具连接、触碰先暂停再询问收回、显式收回持久保留；切页/后台不自行撤销。不要套用已被用户推翻的旧“AI 请求后才授权”方案。

## 4. 开发与验证约定

- 新增和修改的第一方代码注释使用中文；shebang、API标识、第三方版权/许可证保留必要原文，不为翻译改动官方依赖。
- 保留官方 DSH UI/模型/插件/Skills，不另造聊天框，不直接修改已安装的官方 npm 包。原生层负责运行、文件、浏览器和安全边界。
- 涉及安装/下载/导入导出/快照/缓存清理，必须有阶段与耗时反馈；已知总量显示真实进度，未知总量用不定进度，不能造百分比。
- 不把大量磁盘/网络、同步日志重写、全部 WebView 创建/绘制放在主线程。浏览器保留当前页面后台能力；不要调用会影响所有 WebView 的全局 `pauseTimers()`。
- 完整页面截图不能退回原生小窗裁切。`enableSlowWholeDocumentDraw()` 是不可关闭的进程级设置；改动它或进程边界必须重新验证页面尾部像素、AI 缩放和收起小窗，不能只看 DOM。
- 业务代码改动先补合适的回归；构建、JVM、lint 与 Android 仪器测试分别记录。不要把跳过、host timeout 或仅存在测试源码计为通过。
- 同一工作目录不要并行运行不同 flavor/init-script 的 Gradle 构建；不要和实际安装同时跑测试。耗时设备任务给足超时，不能因 ADB 端断开就误判测试成功/失败。
- 本轮未修改产品代码时，如实说明只做了文档/脚本验证，保留前一轮产品测试范围，不冒充重跑全套。
- 新增根目录交付文件时检查 `scripts/package-source.py` 的明确清单；`.build`、本机配置、密钥不得变成必要交接依赖。

## 5. 推荐入口

```bash
# 不操作设备；先按上手指南准备 JDK17、SDK34、NDK27.2.12479018。
python3 scripts/check-handoff.py
bash scripts/verify-workspace.sh

# 仅确认无真实数据、已装运行环境、无安装任务的 ARM64 测试设备：
bash scripts/verify-workspace.sh --run-device-tests --confirm-test-data --device <SERIAL>
```

对外报告始终区分开发 debug 包与正式签名、模拟器与真机、合成登录与真实微信登录、无模型诊断与实际模型任务。完成局部工作不能等同于整个产品已正式验收。
