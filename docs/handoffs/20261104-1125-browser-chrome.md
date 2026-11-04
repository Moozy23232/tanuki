# 交接：浏览器布局、浮动导航与标签入口

- 时间：2026-11-04 11:25 CST开始，15时段收尾；状态：实现/专项验证完成，用户授权分效果提交，并追加授权普通推送本功能分支，结果以最终回执为准。
- 分支：`feat/browser-layout`，起点37dcedd，旧功能分支未动。前一记录：[同步与精简](20261104-0949-rebase-liquid-main.md)。

## 用户目标与范围

网址与标题同排、工具栏上移；所有窄屏页面统一浮动导航，地址栏缩小并首次聚焦全选。追加要求按效果拆小commit，标签页改图标/角标且移除12页数量限制。前次微信目录图片受EPERM阻止；本次临时目录截图可读，未绕过任何权限。

## 当前状态

布局与标签改动完成，按三个效果提交；用户已授权推送 `feat/browser-layout`。推送状态文字并入尚未发布的最后一笔，保留三笔结构。

## 本次改动

1. e724ef5 `feat(browser): compact chrome and select address on focus`：AgentBrowserPage紧凑标题/地址栏、首焦全选/二次定位，MainActivity/TanukiUi接线，GlassNavigationBar网页透明底色及对应回归。
2. 68d9487 `feat(ui): float navigation across workspace pages`：TanukiUi统一浮动布局，WorkspaceFilesPage列表/网格尾部避让、DSH占满视口；主活动接线和像素/滚动/WebView保留回归。
3. `feat(browser): add tab badge and remove tab limit`：AgentBrowserPage用叠页图标与角标，真实计数保留在总览/无障碍说明，超过99仅视觉显示99+；AgentBrowserController与BrowserLibrary移除创建、弹窗和恢复时的12页截断。沿用现有懒创建，不扩展其他资源/权限边界。

没有另建worktree。拆分前源码按SHA保存于忽略目录，第二笔恢复后产品/测试文件与原15项通过版本逐字节相同；旧交接含独有验证事实，未无理由删除。相关入口说明同步在BROWSER-WORKSPACE。

## 验证记录

- 推送收尾只更新交接文字，不改产品、不操作设备；本次重跑交接、源码打包/SHA、公开候选与暂存检查，产品测试沿用下述结果。

- 两笔布局提交分别执行 `:app:assembleModernDebug :app:assembleModernDebugAndroidTest` 通过，没有把前轮设备结果冒充拆分后的重跑。
- 标签改动后执行 `:app:assembleModernDebug :app:assembleCompatDebug :app:assembleModernDebugAndroidTest :app:testModernDebugUnitTest :app:lintModernDebug`：构建成功，97 JVM无失败/跳过，modern lint0错误/71警告/1提示。JDK17，独立init相对公开workspace验证脚本仅把debug后缀改为 `.uipolish.workspacecheck`。
- 核验package/target与无运行任务后，`Tanuki_UI_API36` / `emulator-5554` 使用独立包逐项 `am instrument -w -r -e class <类#方法>`，最终11项无失败/跳过/超时：
  - AgentBrowserControlTest：moreThanTwelveTabsCanBeCreatedByRpcAndRestored、authenticationOriginAndEndpointAreStrict、forbiddenUrlsAndStaleDocumentsFailWithoutNavigation。
  - BrowserWorkspacePresentationTest：tabOverviewCanCreateBeyondTwelveWithAnAccessibleBadge、compactChromeSharesTitleRowAndWebPixelsShowBehindNavigation、multiPageOverviewBookmarksAndHistoryUseNativeControls。
  - FilesBrowserPresentationTest：地址栏真实触摸/全选、320dp暗色200%字体、canonical授权/切页三项。
  - SetupPresentationTest：actualDshWebView与filesListExtends两项，后者是合成滚动列表。
- 新用例在旧12页限制上先失败，修正后RPC创建/恢复15页、原生总览继续新建、真实手势打开第15页、无手势弹窗仍拒绝均通过。中途新用例误点同名标题、用无障碍点击代替真实手势分别失败，改正确入口与既有MotionEvent模式后通过，没有放松产品断言。
- 暗色截图改用临时空白标签并恢复原页，避免既有预览网页混入夹具；增加成功创建断言后另重跑此项通过。正常字号14角标和暗色200%截图已核对；原始截图不打包。
- 测试APK留存后恢复正常Studio开发包输出并核验身份，未安装正常包；设备仅打开独立预览包。

### 前轮范围（不累计本轮）

组合布局双debug/AndroidTest、97 JVM与15项设备回归通过，lint同为0/71/1。涵盖真实文件编辑/复制、多页/查找/缩放/截图/下载、授权重建及WebView保留。旧布局回归先失败；曾修正漏Column导入、单向查找滚过下载目标、实验IME接口、输入节点标签屏蔽文本选择范围等问题。更早液态分支的8项也不计入本轮。

## 未完成与暂停项

实现无待修项。未测真机/低API/真实DSH模型任务/大量标签内存压力；主线既有退出全屏systemBars断言未修复或重测。API Key保存继续暂停。

## 下一步

仅普通推送 `feat/browser-layout`，不改main或旧功能分支；随后等待PR审阅。

## 约束与风险

原生首页/文件/设置有背景模糊，DSH和浏览器只是透明叠放，不抓取/重绘WebView、不改整页绘制开关；列表末尾用可滚动padding，IME占底收起导航，宽屏侧栏与全屏规则保留。不设固定标签数量上限不代表无限内存；保留16 MiB元数据读取、URL/协议边界和弹窗手势门槛。不改登录/运行/文件安全，不清数据、卸载或覆盖正常开发安装。

## 产物与校验

日志、测试APK与截图只留忽略目录；正常Studio输出恢复。每笔提交前检查交接印章、源码候选/外部SHA、Git候选与实际暂存内容；源码包以最后一次重新生成的 `artifacts/sources/Tanuki-0.1.3-source.zip` 为准。版本仍0.1.3/5，不动正式发布目录、tag、main或旧功能分支。
