# 交接：全屏退出顶部留白

- 时间：2026-11-04 19:11 CST开始；状态：修复/专项验证完成，两笔本地提交；用户追加授权普通推送本功能分支，结果以远端回执为准。
- 分支fix/browser-floating-navigation，基线4509972；前一悬浮修复cbe9e9c已提交，必要结果归入[测试现状](../TEST-REPORT.md)。

## 用户目标与范围

先提交悬浮修复，再检查并修复浏览器进入/退出全屏后顶部多一行空白。保留两笔改动，不混入中央人/AI按钮或发布操作。

## 当前状态

复现地址/网页顶部从139/528变为267/656，重复128px状态栏占位。主窗口创建时统一edge-to-edge，全屏只切系统栏及刘海策略，不再切换decorFits；Compose成为唯一占位处理方。三次往返、按钮和系统返回路径位置/高度恢复，WebView与草稿/文档保留。

## 本次改动

- MainActivity、DshWindowMode：上述窗口策略，产品改动仅两处；不靠负padding，不改全局整图设置。
- BrowserWorkspacePresentationTest：新增连续往返真实坐标回归；修正旧组合系统栏误判，同时严格检查状态/导航栏隐藏及完整初始状态恢复。过渡帧先等原生栏出现，避免通用查找器误滚动网页。
- SetupPresentationTest：DSH退出后边界/高度与真实绘制复核。入口与当前状态同步。

## 验证记录

- 推送收尾只补交接授权并并入尚未发布的最后一笔；产品/测试源码不变，只重跑交接、源码/SHA与公开检查，不操作设备、不把原产品结果算作新测。

- 新坐标回归在旧代码先失败，已有全屏用例也失败。产品修复后坐标通过，旧用例仍失败；诊断表明API36在进入前/退出后均为status/nav真、caption/combined假。修正测试后通过；这是两个不同问题，未把修改断言冒充修复产品留白。
- 实际modern/compat debug、modern AndroidTest、testModernDebugUnitTest、lintModernDebug成功：97 JVM，0失败/错误/跳过；modern lint0错误/71警告/1提示。
- 核验包名/target和无真实任务，复用Tanuki_UI_API36/emulator-5554、WebView133，仅独立com.tanuki.modern.uipolish.workspacecheck、0.1.5/7 debug覆盖。
- `am instrument -w -r -e class <类#方法>`：13项UI专项全过137.4秒（新往返、原全屏、空页、透显、可选避让、后台/重建、DSH全屏/固定控件、文件尾部、玻璃像素、地址/暗色200%、整页/AI缩放/小窗）；另2项DshFileUpload的ClipData与系统SAF上传全过8.3秒，共15个不同用例。添加原生栏等待后两项往返复跑10.0/9.2秒通过，不重复累计。无跳过、host timeout或中断。
- 同字节APK已固定复制，签名、身份/code7、SHA和公开扫描通过；正常Studio输出恢复且未安装。前轮12项、上游57项不计本轮新跑。

## 未完成与暂停项

本轮实现无待修项，真机/低API仍需用户复验，不把API36结果泛化。真实模型、长期/大量页面、完整环境升级未验。中央控制按钮与API Key保存继续暂停。

## 下一步

仅普通推送fix/browser-floating-navigation，不改main/旧分支或代发布。用户用包含两项修复的新独立APK复验；中央按钮待后续要求。

## 约束与风险

不清数据、卸载、恢复快照或覆盖正常开发安装。维持浏览器显示偏好、权限/上传边界、同WebView及全页截图路径。只改系统栏占位归属，不修改官方DSH包。

## 产物与校验

artifacts/builds/browser-fullscreen-fix/Tanuki-browser-fullscreen-fixed-debug.apk，独立开发签名、ARM64、0.1.5/7；可覆盖之前独立测试包。SHA256SUMS/说明同目录，当前匹配源码候选为artifacts/sources/Tanuki-0.1.5-source.zip；正式release目录不动。

提交前删除已完成1750过程交接，12项验证、实验失败/撤回和未测边界已归入TEST-REPORT；1632发布交接在第一笔提交前归档，发布SHA/57项范围仍保留。当前目录仅保留本记录/模板/印章，刷新交接、源码/SHA、候选/索引与diff检查。
