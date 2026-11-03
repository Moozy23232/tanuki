# 交接：产物目录分类与旧包归档

- Agent/会话标识：主 Agent / artifact-layout
- 时间：2026-11-03 16:07 CST 开始
- 状态：已完成分类迁移、43项宿主回归及交接/源码/归档校验；按用户追加授权提交推送
- 前一记录：[v0.1.1 产品实现与验证](20261103-1316-login-app-update.md)

## 用户目标与范围

- 用户在 `artifacts/` 根目录看到旧 0.1.0 APK，要求合理组织该文件夹。
- 按发布附件、构建中间产物、源码与历史归档分类；明确当前 v0.1.1 取包位置，防止把被撤回的 0.1.2 当最新版本。
- 同步打包入口，避免下次生成又污染根目录；旧包只移动归档，不直接删除。应用版本保持 0.1.1/3，不重编 APK、不操作设备。
- 开始时未获新的Git授权；收尾用户明确要求「结束后push」，因此本轮提交并推送脚本/文档，不上传产物或发布Release。API Key保存继续暂停。

## 当前状态

- 接手 `main` 为 `50da4b3`，工作树干净；前轮源码已推送。
- `artifacts/releases/0.1.1/` 含最终6个附件；0.1.2 是被用户更正前的未发布中间包，根目录有0.1.0开发APK和多个源码ZIP、校验文件。
- 原生对应源码由 `package-native-sources.sh` 生成，`package-source.py` 显式纳入两个附件；迁移必须同步脚本和校验相对路径。

## 本次改动

| 文件 | 改动与目的 |
|---|---|
| 本记录、`docs/AGENT-STATE.md` | 记录目录整理授权、起始状态和验证边界 |
| `artifacts/`（忽略的生成产物） | 发布/源码/中间包/历史分类，归档旧0.1.0与撤回0.1.2，保留全部旧字节，新增归档清单和根目录README |
| `scripts/package-source.py`、`package-native-sources.sh` | 写入 `sources/` / `sources/native/`，收集白名单同步，校验清单使用各目录正确相对路径 |
| `scripts/prepare-app-release.py`、`sign-release.sh` | 正式签名中间产物进入 `builds/production/<版本>/`；发布附件从分类目录收集，仍为扁平6文件 |
| `scripts/test-app-release.py`、`test-agent-workflow.py` | 分类输出、归档根名/校验/说明副本、白名单、实际模拟签名与收集回归 |
| `docs/ARTIFACTS.md`、`AGENTS.md`、上手/发布/上传/测试说明 | 明确正确取包目录、历史包禁用及长期生成规则；原生原始许可不改 |

## 验证记录

| 实际命令 / 操作 | 环境与结果 | 证据与限制 |
|---|---|---|
| 阅读状态/前一交接/上手/README/安全，`git status --short`、文件清单与打包路径检索 | 完成；工作树干净、目录混有历史及现行包 | 没有读取真实数据、密钥或设备 |
| `python3 scripts/test-app-release.py`、`test-agent-workflow.py`、`test-publication.py` | 12+12+19项通过；没有运行真实Gradle/ADB/正式签名 | 新增回归先红灯，修改路径和补fixture后全过 |
| `bash -n scripts/package-native-sources.sh scripts/sign-release.sh` | 通过 | 原生源码实际打包和生产签名未运行；签名分类输出由合成可执行fixture验证 |
| 迁移前后SHA-256核对，根目录/发布目录断言 | 原22个文件的所有原始内容均保留，归档16个文件；根目录无APK/ZIP，有效发布仅0.1.1 | 初次回归失败导致整条 `set -e` 命令在迁移前退出，补齐fixture后才执行迁移 |
| `check-handoff.py --record` 与无参数、`package-source.py`、`check-publication.py --git --archive artifacts/sources/Tanuki-0.1.1-source.zip` | 通过；279个公共指纹文件、282个源码ZIP条目；工作树280/index278（尚未暂存新记录时），0边界发现 | 收尾文字后再刷新/打包并核对实际暂存，不将旧index数量当最终提交内容 |
| 源码、原生、当前发布、归档清单的 `shasum -a 256 -c` | 全部通过；再次确认原22份内容保留、当前APK/更新清单/原生tar不变 | 发布目录仍6文件；旧清单保留原文，不当迁移后路径执行 |
| 独立临时目录解压源码ZIP，运行交接/公开边界/12项发布回归及原生校验 | 全部通过；无APK/历史archive混入源码ZIP，原生附件在新白名单路径 | 前轮97项JVM、20项模拟器不计本轮重跑 |

## 未完成与暂停项

- 本轮分类、迁移、脚本和公开归档验证均完成，无剩余实现项。不改变前轮真实账号、完整运行环境、未来系统更新全链等待验证结论。
- API Key 保存仍由用户暂停。

## 下一步

1. 用户从 `artifacts/releases/0.1.1/` 取APK和完整6个发布附件，不使用 `archive/`。
2. 后续新版本先确认版本号，按发布脚本收集附件并同步 `docs/ARTIFACTS.md` / 当前状态；不在根目录散放包。
3. 本次已获追加commit/push授权；复核交接保留边界、实际暂存和最终源码包后提交至origin/main，最终hash/远端结果由执行回执给出。

## 约束与风险

- 不按文件名把旧包改成新版，不改变APK字节/签名，不把被撤回0.1.2放在有效发布目录。
- 保留历史清单原文，迁移后如路径已失效，以新增归档清单说明和验证；不为旧校验失败覆盖历史事实。
- Git前复核：当前记录及三份旧交接仍需保留。`20261103-1316-login-app-update.md` 有独有登录/覆盖验收细节，`20261103-1051-dsh-resolution-cache.md` 有组件锁/性能未测边界，`20261102-2226-tanuki-builds.md` 有构建/迁移原始依据；本轮没有结论已全部迁走的过期记录，不按日期盲删。此前已删除的6份过期流程记录不恢复。
- 新增回归初始1失败/3错误，随后实现；首次工作流12项中1项合成fixture缺少 `docs/ARTIFACTS.md`，补fixture和分类产物断言后通过，未改变或关闭密钥校验。
- 原生tar与APK字节保持原样，只更新其存放/收集路径；脚本未实际执行生产签名或原生重打，不冒充已经验证这两个真实流程。

## 产物与校验

- 当前发布保持 `artifacts/releases/0.1.1/`，APK/更新清单不改；完整目录树在 `docs/ARTIFACTS.md`。旧0.1.0在 `archive/0.1.0-dev/`，撤回0.1.2在 `archive/0.1.2-withdrawn/`，整理前源码/原校验在 `archive/0.1.1-before-layout/`。
- 源码ZIP改为 `artifacts/sources/Tanuki-0.1.1-source.zip`，仍用 `artifacts/SOURCE-SHA256SUMS` 作为根校验入口，内容指向 `sources/`；原生和发布清单都相对于自身目录；归档新增 `ARCHIVE-SHA256SUMS`，不覆盖旧清单原文。
- 已刷新印章、源码ZIP与发布副本并核对外部SHA；收尾文字完成后再跑同样检查。仅公开脚本/文档纳入Git，不提交忽略的APK/归档；未签名迁移、覆盖安装或发布Release。不把源码ZIP自身散列写回归档。
