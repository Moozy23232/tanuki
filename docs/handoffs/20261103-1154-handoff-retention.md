# 交接：Git 提交/推送前清理过期交接

- Agent/会话标识：主 Agent / handoff-retention
- 时间：2026-11-03 CST
- 状态：已完成；规则、4 份清理、宿主回归、交接/源码包及外部 SHA 已检查
- 前一记录：[Release 发布流程核对与未决事项](20261103-1158-release-publishing.md)

## 用户目标与范围

- 用户要求每次 Git 前删除已经过期的交接文档；落实为每次提交或推送前的必做流程，不限制必要的只读 Git 状态/差异检查。
- 删除已被替代且必要结论已归档的过程记录，保留当前记录、模板、有效验证依据和未完成事项；不按日期一刀切，不把 Git 历史或 `.build` 当接手依赖。
- 接续先前发布咨询，补齐 GitHub 网页测试版发布步骤及正式签名/包名/升级边界；用户没有授权发布。
- 本次不修改产品代码、不操作设备，不新增暂存、提交、推送、tag、Release 或上传；登录/API Key 排查继续用户暂停。

## 当前状态

- 规则与 4 份过期记录清理已完成，产品状态及既有验证保持不变；源码提交 `adf9d55` / `b1ef5e3` 已在 `origin/main`。
- 本次文档改动与删除尚未暂存/提交；公开内容指纹、重打源码包、边界/外部 SHA 和独立解压校验均通过。

## 本次改动

| 文件 | 改动与目的 |
|---|---|
| `AGENTS.md` | 固化每次提交/推送前归档必要结论、删除过期交接、修复引用、重做印章/源码包/暂存检查；保留初版 README 简短约定 |
| `docs/PUBLICATION.md` | 增加清理顺序、有效记录保留边界、删除也需明确暂存与检查实际 index 的要求 |
| `docs/AGENT-QUICKSTART.md`、`docs/handoffs/TEMPLATE.md` | 将清理和清理结果记录纳入交付入口/模板；避免删除历史后留下前一记录断链 |
| `docs/RELEASE.md` | 补网页 Pre-release 附件/校验流程，区分开发签名 workspacecheck、正式 modern 和源码推送；未签名包不可安装 |
| `docs/AGENT-STATE.md`、`docs/TEST-REPORT.md` | 更新最新交接与文档验证，保留此前产品测试范围，不把历史文件数当当前数量 |
| `docs/handoffs/20261103-1051-dsh-resolution-cache.md` | 仅修复已清理前置记录的引用，指向组件指南；不改写原产品验证结果 |
| `docs/handoffs/20261103-1158-release-publishing.md` | 修复已清理 Git 记录的引用，如实说明独立收尾被追加要求打断、由本记录统一完成 |
| 本记录、`docs/handoffs/CURRENT.json` | 汇总清理、验证、未决事项并刷新公开内容指纹 |

### 已清理的 4 份记录与结论保留位置

| 已删除文件 | 过期理由与仍有用内容的保留位置 |
|---|---|
| `docs/handoffs/20261102-2308-readme-minimal.md` | 首页精简已交付；当前 README 保留成品，`AGENTS.md` 保留不扩成维护报告的约定 |
| `docs/handoffs/20261103-1020-dsh-download-analysis.md` | 初始下载调查已被阶段确认和修复替代；官方完整性来源、归档下载选源等实现边界保留于 `docs/COMPONENTS.md` |
| `docs/handoffs/20261103-1032-dsh-resolution-timeout.md` | 修复前的超时/更新保留咨询已被当前实现替代；组件指南、更新恢复文档、测试报告及 DSH 修复记录保留现状与未测边界 |
| `docs/handoffs/20261103-1139-git-publish.md` | 源码已完成提交/推送，重复过程记录无需继续堆积；当前状态保留 `adf9d55` / `b1ef5e3` 和推送结果，公开上传边界归入 `docs/PUBLICATION.md` |

保留构建记录 `20261102-2226-tanuki-builds.md`（前轮独有设备/构建验证）、修复记录 `20261103-1051-dsh-resolution-cache.md`（本轮产品证据与设备待测）、发布记录 `20261103-1158-release-publishing.md`（正式签名/版本/包名未决），以及本记录、模板和印章。没有遗留指向已删除文件的 Markdown 链接。

## 验证记录

- 首轮交接刷新因合并标题而失败：`Missing handoff sections: ## 当前状态, ## 本次改动, ## 下一步, ## 约束与风险`。已按模板拆分必需标题，再重跑下表命令；失败没有触发打包或 Git 操作。

| 实际命令 / 操作 | 环境与结果 | 证据与限制 |
|---|---|---|
| 接手阅读状态、前一记录、上手指南、README、模板、AGENTS、PUBLICATION、RELEASE、SECURITY 与各交接；检查 Git 状态 | 已完成 | 原有发布咨询文档改动保留，不回退或自动暂存 |
| 固定文件列表检查常规文件/非符号链接后删除 4 份记录；本地 Markdown 链接检查 | 已完成、无断链 | 当前/模板/仍有效记录保留；不使用按日期自动删除 |
| `python3 scripts/test-publication.py` | 19 项通过 | 新跑宿主回归；不是 Android 产品测试 |
| `python3 scripts/test-agent-workflow.py` | 12 项通过 | 新跑宿主回归；脚本不自动判定自然语言记录是否过期 |
| `python3 scripts/check-handoff.py --record docs/handoffs/20261103-1154-handoff-retention.md`、`python3 scripts/check-handoff.py` | 修正必需标题后通过，257 个公共文件参与指纹 | 收尾文字完成后再次刷新/检查，不沿用旧印章 |
| `python3 scripts/package-source.py`、`python3 scripts/check-publication.py --git --archive artifacts/Tanuki-0.1.0-dev-source.zip`、`git diff --check` | 通过；260 个源码条目含两份原生附件；258 个工作树候选/260 个 index 条目，0 边界发现 | index 数量不表示删除已暂存；下次提交仍需显式暂存删除 |
| 外部 `SOURCE-SHA256SUMS` / `WORKSPACECHECK-SHA256SUMS` / `RELEASE-SHA256SUMS` / `native-source-SHA256SUMS` 的 `shasum -a 256 -c` | 源码/双 APK/原生对应源码均 OK | 仅源码 ZIP/清单随本次文档变化，APK 与原生附件未改 |
| 本地 Markdown 文件链接再次扫描；源码 ZIP 解压至临时独立目录后运行 `check-handoff.py` / `check-publication.py`，断言 4 份过期记录不在 ZIP | 75 个文件链接无断链；独立交接/公开边界通过，归档中无 4 份过期记录 | 不依赖工作区 Git 或 `.build`；不进行设备测试 |

## 未完成与暂停项

- Git 删除与文档尚未暂存/提交；下次获准提交时必须明确暂存删除和更新文件，复查实际 index 不残留本次已判定过期的记录，再推送。不能只看工作树删掉就声称已提交。
- GitHub Release 未发布；正式密钥、包名、单调递增版本/tag 和发布可见范围待用户决定。本机无 `gh` 不妨碍用户按网页流程操作。
- 本次没有重跑 JVM/lint/AndroidTest/设备；此前标准 modern unsigned 构建属于发布咨询验证，不是本次清理的新构建。工作区标准 release 输出不可凭路径当作可安装的 workspacecheck 包。
- DSH 性能/弱网真机、覆盖升级、真实任务和账号迁移仍待验证；登录/API Key 继续用户暂停。

## 关键决策

1. 清理是每次提交/推送的必做人工流程；不加自动按日期删文件的脚本，现有指纹/公开边界检查也不宣称能理解自然语言过期状态。
2. 有效测试证据和未完成记录并非“旧了就过期”；事实必须保留在公开源码中，不依赖已删除记录、Git 历史或本机私有目录。

## 下一步

1. 下次接手先建立新交接，再按新规则在每次 Git 提交/推送前清理过期记录。
2. 获准 Git 提交时明确暂存本次改动和 4 项删除、复查 index，再提交/推送。
3. 源码发布与二进制 Release 分开授权，先选定开发测试版还是长期正式签名路线，再修改版本/构建/发布。

## 约束与风险

- 删除的 Git 跟踪文件尚未暂存，公开边界检查通过不等于实际 index 已清除过期记录；必须在下次提交前明确暂存删除并复查。
- 保留有效验证与未完成事项，不按日期自动删文件；不把“仅测试源码存在”“旧轮通过”或 unsigned 构建当作本次设备/正式发布验收。
- 不读取/输出真实凭据，不进行破坏性设备操作、覆盖真实任务或未经授权发布。

## 产物与校验

- 本次只修改文档/交接，开发签名 workspacecheck 双 APK 和原生对应源码附件不变；源码 ZIP 及 `SOURCE-SHA256SUMS` 因文档变化重打。
- 没有新安装、签名、提交、推送、tag、Release 或上传；`.build`、用户数据、密钥/凭据、原始日志不进入公共归档。
- 最新源码 ZIP 含 260 条目，257 个公共文件参与交接指纹；外部 SHA 已核对，收尾文字完成后再次重打/核对。不把源码 ZIP 自身哈希写回归档内制造循环。
