# 交接：精简初版 README

- Agent/会话标识：主 Agent / readme-minimal
- 时间：2026-11-02 23:08 CST
- 状态：已完成
- 前一记录：[Tanuki 构建与发布验证](20261102-2226-tanuki-builds.md)

## 用户目标与范围

- 将初版 README 大幅精简到几句话；仅改首页及必要交接，不改产品代码。
- 保留脱敏、许可和本地运行/远程模型边界；不处理登录/API Key，不操作设备或上传。

## 当前状态

- README 已缩为标题、三句介绍和三个链接，共9行；详细说明保留在专题文档。
- Git main，无暂存或远端；本次不执行提交或上传。
- 既有产品配置与构建结果见前一记录，不计为本轮验证。

## 本次改动

| 文件 | 改动与目的 |
|---|---|
| `README.md` | 保留 Tanuki 标题、三句介绍及使用/许可入口 |
| 本记录、`docs/AGENT-STATE.md`、`docs/handoffs/CURRENT.json` | 记录范围并刷新交接指纹 |

## 验证记录

- Python 检查：README 三句正文、三个有效且未被Git忽略的本地链接。
- `python3 scripts/check-publication.py --git`：250个候选、0个索引项、0发现。
- `python3 scripts/check-handoff.py --record docs/handoffs/20261102-2308-readme-minimal.md` 及默认交接检查：通过。
- `python3 scripts/package-source.py`：252个源码文件，保留完整原生对应源码附件。
- `python3 scripts/check-publication.py --archive artifacts/Tanuki-0.1.0-dev-source.zip`：0发现；`shasum -a 256 -c SOURCE-SHA256SUMS` 通过；归档内README与工作树一致。
- 以上均为本轮宿主文档/归档检查；未执行构建、JVM、lint、Android 或模型测试。

## 未完成与暂停项

- 本次无未完成事项，检查未出现失败。
- 登录/API Key 继续暂停；设备和正式发布不在范围。

## 下一步

1. 等待用户明确的新任务，不自行恢复设备测试或产品排障。
2. 后续公开文件变化须再次刷新交接及源码包；提交和上传需另行授权。

## 约束与风险

- 详细限制和构建步骤仍在专题文档，不用精简首页暗示产品已全面验收。
- 不读取凭据或设备数据，不暂存、提交或推送。

## 产物与校验

- APK 不变；已更新 `artifacts/Tanuki-0.1.0-dev-source.zip` 和 `artifacts/SOURCE-SHA256SUMS`。
- 本记录收尾后再次刷新交接、重打源码并检查；最终ZIP散列只保留在外部校验清单，不回写归档正文。
