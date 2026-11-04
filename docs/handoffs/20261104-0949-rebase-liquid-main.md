# 交接：同步 main 与精简液态玻璃改动

- 时间：2026-11-04 09:49 CST开始；Agent：rebase-liquid-main。
- 状态：同步、精简与专项验证完成；按用户最新授权统一提交/推送功能分支，执行结果以最终回执为准。
- 前轮必要验证已归入 [TEST-REPORT](../TEST-REPORT.md)，重复过程记录按用户要求清理。

## 用户目标与范围

拉取main，优先rebase；随后用户启用ponytail-lite精简差异，并于10:12授权提交、推送。固定根目录，仅推送 `feat/liquid-glass-navigation`，不改远端main，不恢复API Key排查。

## 当前状态

- main/origin/main为 `f02d60b`；上游squash `ece5c82` 与原UI PR `5e08178` 树完全相同，只重放颜色提交：`4a9522e` → `c6b0adf`，range-diff补丁一致。
- 本次提交包含液态玻璃、新工具链与必要许可/精简说明；0.1.3/5、Provider隔离与有界只读授权保留。
- 本地 `backup/liquid-before-main-20261104-0949` 分支和具名stash保留，没有新建worktree。

## 本次改动

- WIP恢复只有状态、报告、印章和两份生成清单冲突；产品源码无冲突。原液态UI/测试四文件SHA与备份相同。
- 删除重复过程交接，仅保留本记录；验证摘要归入TEST-REPORT，状态只留当前入口，不改写上游历史证据。
- 撤回批量JSON/表格刷新，注明清单是main发布基线，不冒充当前依赖；发布前再用已有脚本生成。
- 两个新库复用仓库及APK已有Apache全文，只保留各自版权NOTICE，不删除主线旧许可。保留手势/像素/无障碍回归，未重构手势实现。
- SDK36可空标注适配：`ApkInstallAccess` 缺applicationInfo时按flags=0拒绝授权，测试相应显式非空检查；不扩大授权。

## 验证记录

- 接手 `check-handoff.py`：前轮286文件指纹通过；fetch与main快进成功，rebase无冲突，5处WIP元数据冲突已解决。
- `./gradlew -PtestBuildType=debug -I .build/ui-validation.init.gradle :app:assembleModernDebug :app:assembleCompatDebug :app:assembleModernDebugAndroidTest :app:testModernDebugUnitTest :app:lintModernDebug`：通过，97 JVM无失败/错误/跳过；lint0错误/71警告/1提示。init复用公开 `scripts/workspace-verification.init.gradle`，仅将debug后缀换为 `.uipolish.workspacecheck`。
- 核对package/target后，在 `Tanuki_UI_API36` / `emulator-5554` 只覆盖独立合成包，以 `am instrument -w -r -e class <类#方法>` 跑8项全过：SetupPresentationTest的3个glassNavigation用例及actualDshWebViewSurvivesFullscreenWithoutReload；ApkInstallAccessTest的预读授权、旧VIEW无预授权、旧URI/到期回收、外部URI拒绝4项。无超时或跳过。
- APK内共享Apache全文、两份NOTICE与源文件逐字节一致，未重复嵌入新库LICENSE全文。
- 首次构建因重复复制已有Apache静态资产失败；删掉多余复制逻辑后通过。恢复主线旧许可只影响资产，不改变上述已测代码。
- 前轮97 JVM/10 E2E属于整合前历史，不累计为本轮结果。本次推送收尾只更新授权状态，不重计产品测试。fetch确认main仍为f02d60b，远端目标分支尚不存在，使用普通push。

## 未完成与暂停项

本次实现无待修项。低API、真机、完整环境/真实模型及实际系统安装确认页未本轮验证；API Key保存继续暂停。

## 下一步

推送后等待用户审阅/创建PR；不自动合并或发版。安装前核验APK身份，正常开发包与独立验收包不混用。

## 约束与风险

不重复应用已合入UI，不恢复上游已归档交接；不清数据、不换签名或覆盖正常开发包。不删除安全校验、无障碍或必要版权以缩小diff。

## 产物与校验

提交前复核：前轮重复过程记录已清理；当前记录和主线4份交接仍含独有验证/未测依据，无新增可删项。正常Studio开发输出已恢复并核对包名，未安装。交接刷新、源码候选、公开边界（0发现）、SHA及diff检查通过，未触碰正式发布目录。原始日志、截图和stash留本地；旧过程记录的实现/验证/未测结论已迁入本记录、TEST-REPORT及现有回归源码。
