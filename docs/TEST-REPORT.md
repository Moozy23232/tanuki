# 测试现状

Tanuki验证目标：`com.tanuki.modern.workspacecheck`，命名空间 `com.tanuki`，ARM64、minSdk26/targetSdk34。debug/release构建与证书、宿主与设备执行必须分别记录，源码中有测试不等于已运行。

## 全屏往返与悬浮修复（2026-11-04，独立debug）

- 悬浮修复cbe9e9c：浏览器默认恢复透显，可在通用设置主动开启/保存网页底部控件避让，DSH保护不变。此前双debug/AndroidTest、97 JVM、12项API36专项通过（97.5秒），lint0/71/1；避让项补网页可视帧同步另过，不重复计数。原生宿主不裁切实验仍无法透显，已撤回；不存在的图标编译错误改为复用已有图标。旧过程交接归档删除。
- 新增全屏往返坐标回归先失败：地址/网页顶部由139/528变为267/656，重复128px状态栏占位。MainActivity在创建时统一edge-to-edge，DshWindowMode不再来回切换decorFits，占位只由Compose处理；不使用负padding、不重建WebView。
- 旧systemBars断言在API36另有误判：初始和退出均为 `[status=true, navigation=true, caption=false, combined=false]`。手机没有窗口caption，不能要求组合systemBars全部可见；现明确要求状态/导航栏进入全屏都隐藏，退出恢复完整初始可见性。新坐标断言独立验证产品缺陷，没有用测试修改掩盖重复占位。
- 本轮新跑双debug/AndroidTest、**97 JVM无失败/跳过，modern lint0错误/71警告/1提示**；Tanuki_UI_API36/emulator-5554、WebView133、独立uipolish.workspacecheck包：**13项UI专项全过137.4秒，2项ClipData/系统SAF上传全过8.3秒，共15个不同用例**。往返用例增加原生栏可见等待，避免通用查找器在过渡帧滚动网页，随后2项复跑通过，不累计。
- 覆盖3次按钮/系统返回往返、地址/网页坐标及高度、状态栏/导航栏、同WebView/草稿/timeOrigin，DSH边界与退出绘制、空页、透明像素、可选避让、文件列表/玻璃、地址栏/暗色200%、后台重建、完整截图/AI缩放/小窗和上传。正常开发安装未覆盖；真机/低API与真实DSH任务未验，不将API36结果扩大为所有平台。当前交接见[全屏修复](handoffs/20261104-1911-fullscreen-insets.md)。

## v0.1.5/7 PR #3 release 验证（2026-11-04）

- 用户明确测试正常后push并打包0.1.5。固定[PR #3](https://github.com/leezzou/tanuki/pull/3) head `c95fa9fcc80a78489a1bc2d7fc5710dfbe30857a`，merge-base为最新main `a2a591331100e94b8425d7b7c9d31019f459827c`，ahead6/behind0；远端Git引用复核一致。列表缓存曾返回旧PR #2，匿名API失败，改用独立PR页面与Git refs确认。保持0.1.4的上传/AI只读修复，默认版本改0.1.5/code7，高于公开清单与已安装最高6。
- 新增真实触摸回归先复现 **浏览器与DSH底部按钮均被原生浮动导航拦截、误切页（2失败，23.891秒）**。修复仅给两个AndroidView预留bottomInset，含导航、间隙与系统区域；键盘/全屏/宽屏无浮动导航时为零。保留同一WebView，不改官方DSH、完整截图开关或权限；旧网页透显断言改为安全视口实际绘制，两项真实触摸断言保持。
- 原候选release/AndroidTest、compat debug、release JVM/lint构建4分39秒成功；修复后同命令再构建 **3分12秒成功**。最终 **release JVM97项，0失败/错误/跳过；release lint0错误/71警告/1Hint**。宿主发布18项（5.679秒）、工作流12项（28.474秒）、公开边界19项（14.171秒）通过，49项不与JVM或设备混计。
- 最终实际APK `com.tanuki.modern.workspacecheck`，0.1.5/code7、ARM64、min26/target34、non-debuggable，**10,370,132字节**；SHA-256 `a21a8ae304c08857b1881f17668f442cab461d14c3c283208ecd5e4aa12dbdf2`，开发证书 `371dc301e7bb4d5cb2840510855f191eeb2f1ce4d058618361419409654cd84f`。app/test包名与instrumentation target一致，同证书覆盖；两provider独立/非导出，DEX父类与各自路径资源核对。依赖相对main无变化；APK内62项许可资产逐字节一致、79条记录/78个坐标、Kyant两NOTICE及共享Apache全文匹配。
- 复用 `Pixel_7_API_34_arm64 / emulator-5572`、ARM64/API34/WebView159，确认无安装/真实任务，仅合成数据。旧0.1.4/6覆盖前种私有标记 **1项通过，4.938秒**，没有在覆盖后重种。原失败候选上15个真实HTTP页面的草稿/文档保留、只读RPC、关闭13页恢复与按需加载 **生命周期2项通过8.521秒**；该数字不代替最终APK结果。
- 最终APK重点 **4项通过，48.044秒**：浏览器/DSH的fixed-bottom输入焦点和两个按钮接受真实屏幕触摸且不误切页、紧凑标题/地址同行/安全视口实际像素、DSH全屏同文档/真实像素。关联53项首跑 **51通过/2失败，318.326秒**，两项地址栏旧测试把20dp箭头仍按文字颜色数量检测，7/8色误判。仅适配测试为箭头形状/对比/留白、真实URL与按钮状态同时收敛；原真实触摸导航、首焦全选/再次点击光标断言保留。test-only编译35/42/39秒；前两次复测因子节点未合并disabled语义（2失败，12.292秒）及祖先缓存未刷新（1通过/1失败，11.617秒），改为有界新树/祖先refresh轮询后，最终 **2项通过20.282秒**。产品APK字节始终不变，按方法集合去重 **57个不同用例最终全部通过**，无跳过/host timeout/仪器中断；覆盖前私有标记读回原值后清理，不重种。
- 独立APK检查最初误把apkanalyzer数值资源ID当作XML名称，改读APK资源表后通过；一次source误用zsh改bash，未改APK。交接初次刷新因PR压缩STATE标题后未插入当前记录链接失败，补链接后通过；不计作产品失败/成功。候选APK公开扫描0发现，旧0.1.4六附件SHA全部通过且不改写。8张专用合成截图视觉检查：底部控件完整露出、紧凑工具实际绘制、全屏页面满幅无空白、普通/暗色200%地址栏可达；键盘截图导航在IME上方，输入可用，不宣称导航必定收起；不据截图宣称旧系统栏边界已修复。公开边界19项在交接清理后复跑通过（1.332秒），不重复累计49项宿主数量。
- 未验：MIUI真机、真实账号/模型、完整Ubuntu/Node/DSH运行环境跨APK升级、弱网/低API、长期后台及大量复杂标签资源压力、安装授权期限进程死亡恢复。既有浏览器退出全屏systemBars断言未修复且不在本轮通过范围；API Key保存继续暂停。15个小页面回归不能证明任意数量复杂标签的资源安全。

### v0.1.5 可复现的57项选择

配置JDK17/SDK后实际构建命令：

```bash
source scripts/env.sh
./gradlew -PtestBuildType=release -PdevelopmentReleaseSigning=true \
  -PtanukiVersionName=0.1.5 -PtanukiVersionCode=7 \
  -I scripts/workspace-verification.init.gradle \
  :app:assembleModernRelease :app:assembleCompatDebug :app:assembleModernReleaseAndroidTest \
  :app:testModernReleaseUnitTest :app:lintModernRelease
```

核对APK身份后，明确serial、串行 `adb -s emulator-5572 install -r <app.apk>` 与配套AndroidTest；按下列选择拆为4项重点与53项关联：`adb -s emulator-5572 shell am instrument -w -r -e class <逗号分隔的类或类#方法> -e verifyUpgradeMarker true com.tanuki.modern.workspacecheck.test/androidx.test.runner.AndroidJUnitRunner`。标记验证只在关联组执行；旧包先种，再覆盖、读原值并清理，不直接复跑删除后的标记。

整类：`DshFileUploadTest`（5）、`AgentBrowserControlTest`（14）、`WebAuthenticationTest`（2）、`DshViewportTest`（1）、`BlobDownloadTest`（1）、`ApkInstallAccessTest`（5）、`FilesBrowserPresentationTest`（5）、`ApplicationUpdateNativeTest`（3）、`ApplicationUpdatePresentationTest`（1）、`BrowserTabLifecycleTest`（2）。另选以下方法，完整类名前缀为 `com.tanuki.`：

```text
BrowserWorkspacePresentationTest#compactChromeSharesTitleRowAndWebViewportClearsNavigation
BrowserWorkspacePresentationTest#fixedBottomHttpControlsAcceptRealTouchesWithoutChangingNavigation
BrowserWorkspacePresentationTest#revokedAiKeepsDshBrowserEntryAndUserPageVisible
BrowserWorkspacePresentationTest#defaultConnectionAndTouchTakeoverSurviveBackgroundAndRecreation
BrowserWorkspacePresentationTest#tabOverviewCanCreateBeyondTwelveWithAnAccessibleBadge
BrowserWorkspacePresentationTest#multiPageOverviewBookmarksAndHistoryUseNativeControls
BrowserWorkspacePresentationTest#emptyBrowserCanExitFullscreenWithoutFocusingAMissingAddressField
BrowserWorkspacePresentationTest#displayModesSearchFindZoomCaptureAndDownloadAreUsable
BrowserOverlayPresentationTest#revokedAiCanStillOpenAndViewBrowserOverlay
BrowserOverlayPresentationTest#fullDocumentAndAiZoomAreIndependentOfSmallWindow
BrowserOverlayPresentationTest#displayDensityKeepsCollapsedControlsReachableAndNotificationStopIsFinal
SetupPresentationTest#glassNavigationBlursPixelsAndKeepsTabsClickable
SetupPresentationTest#glassNavigationDragPreviewsThenCommitsOnceAndCancels
SetupPresentationTest#glassNavigationNullBackdropDragsInRtlAndKeepsLargeTabsClickable
SetupPresentationTest#actualDshWebViewSurvivesFullscreenWithoutReload
SetupPresentationTest#fixedBottomDshControlsAcceptRealTouchesWithoutChangingNavigation
SetupPresentationTest#filesListExtendsBehindNavigationAndScrollsTailClearWithoutReloadingDsh
SetupPresentationTest#setupGuideFirstStepSourceVersionAndLocalThemeScreenshots
```

## PR #3 合并验证（2026-11-04，debug）

- 将main/a2a5913合入浏览器分支，保留上传、AI查看/操作分离和浏览器布局/浮动导航/标签改动。双debug、AndroidTest、97 JVM通过；modern lint0错误/71警告/1提示。
- `Tanuki_UI_API36` / `emulator-5554`，独立 `com.tanuki.modern.uipolish.workspacecheck`、0.1.4/6，**22项全过，256.2秒**：查看/操作/私有页/30秒/15页恢复5，上传5，完整浏览器4，小窗/整页2，地址栏2，DSH/文件列表/导航拖动4；无失败、跳过或超时。正常开发包未覆盖；本节为作者历史验证，不计作0.1.5本轮结果。
- 作者独立init只将debug后缀改为 `.uipolish.workspacecheck`，WebView133；工作流12项、公开边界19项通过。删交接后曾因记录文字未变而印章拒绝，补实际删除说明后通过；Studio普通包仅编译/核验，未安装。
- 合并前浏览器分支d3aedc6保留三笔提交：e724ef5紧凑地址/首焦全选、68d9487全页浮动、d3aedc6图标角标/取消12页截断。0.1.3组合版本先有97 JVM/15项布局回归；标签调整后另有97 JVM/11项专项，lint均0/71/1，不与本轮累计。包含文件编辑复制、下载/截图、授权/重建、实际像素和文档保留，文件尾部用合成列表验证。
- 旧布局/12页限制曾被新增回归先复现；漏Column导入、实验IME接口、输入节点标签屏蔽选择范围、查找滚过目标、误点同名标题和无障碍点击不算真实弹窗手势均已修正后复测，不把中间失败或重复运行计入通过数。源码与当前设备用例保留，旧过程记录删除。
- 真机/低API/真实DSH任务/长期及大量标签压力未验；既有退出全屏系统栏断言仍排除，API Key保存继续暂停。本轮不是正式release验收。

## v0.1.4/6 release 验证（2026-11-04）

- 用户确认0.1.4并授权push；main同步用户squash合并PR #2后的dbd30f9，tree与已测PR37dcedd一致，保留上传和AI只读修复。版本code6高于当前设备/本地及公开清单最高5；公开v0.1.3/5与v0.1.2/4已核对。
- 新增已测APK固定字节封装参数，推送后不重新编译：`--verified-apk`与`--verified-apk-sha256`成对验证，临时固定副本、实际APK身份/签名与发布副本均复验。默认构建和production重新签名路径保留；18项宿主回归通过，含错误hash/身份拒绝、原路径被覆盖仍保留已测字节。工作流12、公开边界19也通过，共49项宿主测试。
- 重新采集实际79个运行归档（78唯一坐标），SHA全部匹配，新增60 POM/42上游许可文本；原有5项缺POM许可声明范围保留。最终APK内79项Markdown清单、Kyant两NOTICE与共享Apache全文逐字节核对；JSON仅在源码归档，未把不存在的APK JSON条目当成实际内容。
- 0.1.4/6 release与AndroidTest、release JVM/lint单一Gradle构建 **4分17秒成功**。**release JVM97项通过，0失败/错误/跳过；release lint0错误/71警告/1Hint**。本节是本轮新跑，与下方0.1.3 debug结果分开。
- 实际候选为com.tanuki.modern.workspacecheck、ARM64、min26/target34、non-debuggable、开发证书371dc301…不变；**10,361,052字节**，SHA-256 `97c738c68e6f3ecf96a106db2edc8f5423f0a73ef90a12532c572d809bd989a6`。更新/浏览器provider组件独立且非导出。独立检查最初错误假设APK含JSON清单，按Gradle实际MD资产路径核对后通过，没有修改APK。
- 同一Pixel_7_API_34_arm64/emulator-5572、API34/WebView159，确认无真实任务后同包同证书覆盖。覆盖前旧debug包种合成文件1项通过（2.951秒）；实际release带 `verifyUpgradeMarker=true` 跑 **44项全部通过，249.955秒**，包含下节41项选择与ApplicationUpdateNativeTest整类3项。原值读回并删除标记，不重新创建冒充保留；无失败/跳过/超时/仪器中断。
- 这是开发签名release，不是正式密钥或真机验收；真实MIUI/模型/账号、完整Ubuntu环境升级及既有全屏退出断言仍未全面验。API Key保存暂停。最终封装复用上述已测SHA，输出0.1.4六附件；旧发布目录不改写。封装入口见[发布流程](RELEASE.md)。候选APK公开扫描0发现；测试HTML尾随空格已清理，不改已测APK。上游POM的CRLF/许可末空行保留，格式例外仅限原始许可资料，不豁免公开边界扫描。

## PR #2、上传与 AI 只读组合验证（2026-11-04）

- 固定PR #2 head `37dcedd38766745e226b5133e2a1556a32990963`，基于f02d60b，两个提交本地整合于 `codex/pr2-upload-browser`。main/origin/main未推送；用户先等本地结果，再自行在GitHub合并。组合包包含本轮未提交的上传与浏览器修复，不等于原PR已经包含这两项修复。
- 用户明确“选文件确定后无附件”：旧WebView `parseResult` 在真实159内核对ClipData-only结果返回0个，新解析保留2个；经MainActivity、真实DOM、Blob Worker XHR上传后服务器收到两份完整UTF-8原字节。真实系统SAF单选、取消/重载/销毁、来源及私有URI拒绝共5项通过。content/file直接访问仍关闭，没有改官方DSH包。
- 用户进一步明确AI查看不需要操作权。合法当前连接可读普通当前页的tabs/snapshot/screenshot，人接管时也可读；所有点击/输入/滚动/导航/标签变更仍需操作权，读取不偷偷切页。OAuth临时页读写拒绝，元数据不泄漏；新增3项控制回归和2项原生可见回归均通过。
- 最后组合Gradle构建 **2分23秒成功**：modern/compat debug、modern AndroidTest、**JVM97项，0失败/错误/跳过**；modern lint **0错误/71警告/1Hint**。Gradle8.13、AGP8.13.2、Kotlin2.3.10、compileSdk36，target34/min26；前一组合构建9分59秒和实际79项依赖解析属于中间结果，未替代最后版本。
- 同一专用 `Pixel_7_API_34_arm64 / emulator-5572`、API34/WebView159，核对包名/target/证书后覆盖安装实际debug APK。**两组41项全过**：核心23项139.877秒，关联18项133.720秒；无失败/跳过/host timeout/仪器中断。含上传5、控制13、原生撤权可读2、玻璃导航3、认证2、viewport1、Blob1、安装器5、文件5、DSH全屏1、整页/缩放/小窗1、通知/density1、接管/后台/重建1。
- 第一轮原上传3项失败（7.309秒）仅证明MIME/非法URI/重复回调缺口，直接文件字节本身成功，因此撤回初始contentAccess根因判断。SDK缺失4分33秒及Maven DNS1分53秒失败已补环境/重试解决，不计产品通过。最初组合上传5项均失败（56.671秒），定位为loadDataWithBaseURL无历史URL的测试页与新原生origin校验不符；只改夹具为真实HTTP/loadUrl、焦点首帧与触屏坐标，最终5项通过，未放宽产品校验。
- 宿主工作流12、发布12、公开边界19项本轮通过。测试APK为 `com.tanuki.modern.workspacecheck`、0.1.3/5 debug，开发证书不变；**14,110,161字节**，SHA-256 `fa2765471e57bcc9b1889d9b7f85afc6f2d04a8fede773d810b506460cf01b5b`，副本在builds/workspacecheck/pr2-upload-browser。既有releases/0.1.3六附件校验通过，未改写。
- 真实MIUI/账号/模型、完整Ubuntu/Node/DSH会话及低API渲染未本轮验收；Compat仅编译。既有退出全屏systemBars断言未修复、未计入41项。API Key保存继续暂停。可复现选择见下节，数据边界见[SECURITY](SECURITY.md)。

### PR #2 组合41项的可复现选择

整类：`DshFileUploadTest`、`AgentBrowserControlTest`、`WebAuthenticationTest`、`DshViewportTest`、`BlobDownloadTest`、`ApkInstallAccessTest`、`FilesBrowserPresentationTest`。另选如下方法（完整类名前缀为 `com.tanuki.`）：

```text
BrowserWorkspacePresentationTest#revokedAiKeepsDshBrowserEntryAndUserPageVisible
BrowserWorkspacePresentationTest#defaultConnectionAndTouchTakeoverSurviveBackgroundAndRecreation
BrowserOverlayPresentationTest#revokedAiCanStillOpenAndViewBrowserOverlay
BrowserOverlayPresentationTest#fullDocumentAndAiZoomAreIndependentOfSmallWindow
BrowserOverlayPresentationTest#displayDensityKeepsCollapsedControlsReachableAndNotificationStopIsFinal
SetupPresentationTest#glassNavigationBlursPixelsAndKeepsTabsClickable
SetupPresentationTest#glassNavigationDragPreviewsThenCommitsOnceAndCancels
SetupPresentationTest#glassNavigationNullBackdropDragsInRtlAndKeepsLargeTabsClickable
SetupPresentationTest#actualDshWebViewSurvivesFullscreenWithoutReload
```

官方固定0.2.1-rc.5上传/UI公开包经仓库锁SHA-512核对：File/Blob走Blob Worker XHR原字节上传；只有显式ReadableStream走stream fetch，勿为普通附件引入HTTP流兼容改写。上述用例是合成HTTP页面、MediaStore文件和RPC，不是实际模型任务。

## PR #2 作者的同步后验证（2026-11-04，独立历史）

作者在隔离包/API36 `Tanuki_UI_API36 / emulator-5554` 跑97项JVM、modern lint0错误/71警告/1提示，双debug/AndroidTest编译及8项仪器回归通过：Setup的三个glassNavigation和actualDshWebView全屏，以及ApkInstallAccess的预读授权、旧VIEW无预授权、旧URI/到期回收、外部URI拒绝四项。SDK36可空applicationInfo按flags=0拒绝处理，syncNotices使用Sync防旧资产残留；曾因重复Apache资产构建失败，改为共享全文/保留NOTICE后通过。不是本机API34或本轮0.1.4的新跑结果；低API、真机/真实环境仍未验。

## 液态玻璃分支验证（2026-11-03，整合 main 前）

- Kotlin2.3.10/Compose1.10.3/Backdrop1.0.6 下，双debug/AndroidTest、97项JVM通过；双lint各0错误/70警告/1提示，31项宿主回归通过。API36隔离包的10项E2E通过，覆盖真实拖动/取消/RTL/键盘、大字体、原生采样对齐、WebView切页/全屏及设置/文件/更新界面。
- 当时版本0.1.1/3，不代表本轮0.1.3/5重测；低API/真机/真实环境未验收。归档必要结论后移除重复过程交接，后续结果见[测试现状](TEST-REPORT.md)。

## v0.1.3/5 安装器URI修复与发布包验证（2026-11-03）

- 用户在「设置→应用更新」安装时报MIUI FileProvider未导出拒绝，要求修复推送后生成0.1.3。0.1.2/code4中间包已归档，最终版本0.1.3/code5，保持原workspacecheck包名/开发证书。
- 先补安装器回归，再实现ClipData与系统处理器单文件只读授权、最多10分钟及下次操作/失败回收、进程重建按原期限恢复计时。实际系统确认页仍复现Permission Denial，定位原两个authority共用同一provider组件；改为独立更新/浏览器子类后通过，未开放provider或扩大目录。
- 新类尚未实现时编译29秒失败；实现后有一处ActivityInfo空值编译问题，修正后debug构建/JVM/lint通过（2分35秒）。首次设备5项中4通过，实际安装器确认页等待失败，单例重跑同样失败；系统日志证明获取provider被拒绝。拆分provider后debug构建2分20秒通过，**5项安装器回归全过，4.723秒**。实际系统安装器已读出APK标签并显示确认页，随后取消，没有自动安装。
- 权限回归核对Android真实安装器UID与普通匹配App UID、ClipData/单文件只读、拒绝写入/持久/前缀授权标记、旧文件与到期回收、非更新URI拒绝、不同provider组件及非导出状态。修复后97项JVM全过，debug lint0错误/53警告/1提示；发布12/公开边界19项宿主复跑通过。
- 修复先推送为6cfda3e，再构建最终0.1.3/code5候选release：构建102.7秒、原生对应源码17.4秒，实际APK为com.tanuki.modern.workspacecheck、ARM64、min26/target34、non-debuggable，开发证书不变。APK **7,926,865字节**，SHA-256 `dbbb4cf2647ed00f6a44423c61a4cdaef76bad730a61a3aaced48f1f8d18b6df`；两个文件provider组件独立、均非导出且仅允许URI授权，清单/签名/字节/源码与原生附件校验和公开边界扫描通过。
- 最终release AndroidTest、release JVM及release lint单一Gradle构建1分52秒成功；**release JVM97项全过、0失败/错误/跳过，release lint0错误/53警告/1提示**。同一AVD实际覆盖发布APK及配套test，携带 `verifyUpgradeMarker=true` 运行11类选定 **34项全过，171.826秒**，包含新增5项、真实系统确认页、浏览器/文件/进度相关既有范围与覆盖前合成文件原值；无跳过、host timeout、ADB断线或仪器中断。
- 独立APK核对最初误把合并manifest的provider总数断言为2，忽略AndroidX Startup，改成按updates/browserfiles两个authority筛选后通过；这是核验脚本误判，未修改APK或放宽两者独立/非导出/只读边界。最终收尾文档统一提交后刷新源码与6附件清单，APK必须维持上述已测SHA。
- 这些为AOSP ARM64 API34合成回归；MIUI真机、完整Linux环境、真实账号/模型仍未测，进程死亡后的期限恢复未实际杀进程验证。既有全屏退出断言未在本轮修复或纳入34项通过。完整步骤见[应用更新说明](APP-UPDATES.md)。

## v0.1.2/4 中间包验证（2026-11-03，已归档不交付）

- 用户确认0.1.2并要求先推送本地源码、再打包。主目录已从67216c9快进至用户合并PR后的ece5c82，合并代码树与已测PR 5e08178一致；仅将版本默认值/源码包名更新至0.1.2/4，补齐实际58项依赖及上游许可、同步交接/发布文档，无业务行为修复。
- 公开v0.1.1/v0.1.0附件的GitHub SHA-256与本地旧APK一致，apkanalyzer实际code为3/1，设备与撤回候选均为3；新版code4单调递增，沿用原workspacecheck开发证书。
- 本轮主目录新跑：发布12项、工作流12项、公开边界19项宿主回归通过；97项JVM无失败/错误/跳过，debug lint 0错误/53警告/1提示，Gradle 1分57秒成功。112个本地Markdown链接与diff格式检查通过。
- 准备提交681f55a先推送后，release构建109.1秒、原生对应源码12.8秒成功，实际0.1.2/4、non-debuggable、ARM64、SDK26/34、同开发证书与6附件校验通过；release AndroidTest构建43秒。实际APK覆盖后29项选定回归全部通过（153.118秒），包含覆盖前私有合成标记读回；这组此前未覆盖实际系统安装器读文件，因此不能代替后续URI修复验证。6附件原字节归档到archive/0.1.2-code4-before-uri-fix，旧16归档文件不改，归档索引共22文件。

## PR #1 界面改版隔离验证（2026-11-03，验证时尚未合并）

- 固定 [PR #1](https://github.com/leezzou/tanuki/pull/1) head `5e081784707352f35564890ad139b50580190661`，共同祖先为用户最新 main `67216c9561294e196d5e678c381b09888bc61e30`，ahead2/behind0；收尾远端引用仍一致。两个独立 worktree 的产品代码均保持各自提交原样，主工作区只记验证文档，不自动合并。
- PR 的 `verify-workspace.sh` 构建阶段通过（3分37秒）：Modern debug app/test、97项 JVM（0失败/错误/跳过）、lint 0错误/53 warnings/1 information。另顺序编译 Compat debug 通过（1分9秒），未安装不同包名或在旧 Android 上运行。
- 复用 `Pixel_7_API_34_arm64` / `emulator-5572`，ARM64 API34、WebView159.0.8133.0、专用合成数据，无安装/真实任务。实际 APK 包名、test target、开发证书一致后同包覆盖；覆盖前、PR安装后及main/PR相互覆盖后的合成文件读回均通过。最终保留 PR debug v0.1.1/3，不是前轮发布 APK 或正式签名包。
- PR 首轮受影响 UI 组22项：21通过/1失败（177.584秒）；文件/浏览器安全扩展组40项：39通过/1失败（160.375秒）。覆盖玻璃背景像素模糊/点击/RTL/200%字体、安装步骤与进度、文件编辑复制多选、设置与更新入口、浏览器多页/收藏/历史/搜索/下载、触摸暂停/30秒归还/显式撤销、整页尾部截图/AI缩放/小窗、WebView保留、合成外链/认证与Blob等；未运行依赖已装运行环境的两个用例，不把未选择算通过。
- 失败对照：`BrowserWorkspacePresentationTest#newBrowserPageDefaultsToWindowsAndFullscreenKeepsTheDocument` 在第227行退出全屏后的 `systemBars()` 可见性等待失败，PR 单独复跑仍失败（23.799秒），main 同点失败；根因未修复，不宣称该功能已通过。`BrowserOverlayPresentationTest#displayDensityKeepsCollapsedControlsReachableAndNotificationStopIsFinal` 第303行通知动作缺失，main也同点失败（两项对照共30.540秒）。设备通知权限原为关闭，正常点击原生入口和系统「允许」后，此通知用例 main 1项通过（11.635秒）、PR与合成文件复测2项通过（11.619秒）。通知授权保留，density恢复420。
- 因此本次62个不同设备用例累计61通过、1项既有全屏断言失败；复跑不重复计数，首轮失败不抹掉，无host timeout/仪器中断/跳过。未发现本 PR 新增功能回归；真机、真实账号/模型、完整 Ubuntu/Node/DSH、旧 Android 渲染与正式签名未验收，API Key保存仍暂停。
- 流程缺口：PR原样 `check-handoff.py` 会报 Stale handoff；本地测试副本按规则新增本会话交接并刷新后才执行构建，未关闭检查。合并后须同步测试记录、刷新内容指纹；本次没有提交/推送这些文档。当时交接280指纹文件、源码283条目（含原生源码），公开扫描0发现、SHA通过；源码只含main及验证文档，不交付未合并PR APK，0.1.1发布附件未改。
- 后续用户已在 GitHub squash 合并为 `ece5c82`，0.1.2打包轮快进到该提交；其代码树与上述固定PR一致。同步与新包结果另见[测试现状](TEST-REPORT.md)，不将本节结果冒充新release重跑。

## 产物分类验证（2026-11-03，非产品重测）

- `scripts/test-app-release.py` 12项、`test-agent-workflow.py` 12项、`test-publication.py` 19项均通过；源码分类、原生附件白名单、发布收集、模拟正式签名分类输出和清单相对路径有回归覆盖。两个签名/原生打包 shell 语法检查通过。
- 迁移前记录22个产物文件的SHA-256，分类后逐一确认所有原始内容仍存在，未删除旧APK/源码；当前发布只保留0.1.1，旧0.1.2移入撤回归档。归档清单含16个文件。
- 新增路径回归初始1失败/3错误；实现后通过。首次工作流回归有1项合成fixture缺少新说明文件，补fixture及实际分类输出断言后12项通过，未放宽签名路径保护。
- 本次只改宿主脚本/文档和移动产物，未重编APK、未运行Gradle/JVM/lint/模拟器、未操作正式密钥或重新执行原生源码打包；下述97项JVM与20项设备是前轮结果，不计为本轮新跑。原交接在0.1.2提交前清理，本节保留实际验证与失败，分类和归档校验入口见[取包说明](ARTIFACTS.md)。

## v0.1.1 产品验证：登录、空闲权限与应用更新（2026-11-03）

目标 `com.tanuki.modern.workspacecheck`、`versionName=0.1.1` / `versionCode=3`，开发证书。代码改动先加回归再实现；不是生产证书/真实登录/模型任务的正式验收。

| 范围 | 本轮实际结果 | 限制 |
|---|---|---|
| JVM | **97 项通过，失败/错误/跳过 0** | OAuth 入口/一次票据、空闲/显式撤销、Feed/清单/数字与 URL、字节/文件链接/哈希/APK 身份等；不是设备系统安装链 |
| modern debug 与 AndroidTest 构建 | 成功；明确核对包名、versionName/code、ARM64 和实际开发证书 | `-I scripts/workspace-verification.init.gradle -PtestBuildType=debug -PdevelopmentReleaseSigning=true`，无并行 flavor 构建 |
| debug lint | **0 error，53 warnings，1 information** | 包含 UsableSpace25、RequiresFeature9、GradleDependency7、交互/可访问性等提示；未宣称全部既有或清零 |
| 宿主脚本 | 发布准备 9、工作流12、公开边界回归19，均通过；签名 shell 语法通过 | 合成 badging/证书数据，不读取正式密钥 |
| 复用模拟器 debug | `Pixel_7_API_34_arm64` / `emulator-5572`、ARM64 API34/WebView159，**6 类19 项最终全套通过** | `AgentBrowserControlTest`、`AppBrandingTest`、`ApplicationUpdateNativeTest`、`ApplicationUpdatePresentationTest`、`BrowserIntegrationTest`、`WebAuthenticationTest`；不是前轮14类54项全部重跑 |
| 官方登录合成路径 | 官方平台授权、预开空白异步弹窗/主框架导航、一次性精确 callback、来源/端口/重放拒绝通过 | 未使用真实 DeepSeek 账号；管理 token/Cookie 不复制至网页或日志 |
| 浏览器30秒 | 设备真实等待验证临时接管归还；交互复位/显式撤销不恢复等通过 | 后台本身不撤销；不改变进程级整页绘制设置 |
| 实际点击更新检查 | 原生设置显示「暂无可用于应用内更新的 Release；发布时需附带 app-update.json」 | 公开 Atom/附件，**不调用 GitHub API/不需要 token**；无本版清单时不假装有更新 |
| 原生覆盖文件基线 | 单独 Native 3 项通过，种合成私有文件；发布 APK 覆盖后加 `verifyUpgradeMarker=true` 读回原值并清理合成文件 | 未重新创建冒充保留；不是完整 Linux/真实用户数据覆盖测试 |
| 版本更正前 0.1.2 non-debuggable 包/覆盖后回归 | 一键准备脚本成功，实际 APK 包名/0.1.2/3/SDK26/34/ARM64/开发证书核对；同一 AVD `adb install -r` 后 **6 类20项通过，104.346秒** | 发布目录真实 APK 被测试；没有生产签名/Release 上传，AndroidTest 与 debug 同证书 |

最终用户更正为 v0.1.1 后重新执行 release/AndroidTest 构建、97 项 JVM 与 debug lint，结果仍如上表；`prepare-app-release.py --version 0.1.1 --version-code 3 --channel workspacecheck` 成功。覆盖前重新种合成私有文件，安装最终 0.1.1 APK 后加 `verifyUpgradeMarker=true` 跑同6类，**OK (20 tests)，113.154秒**，无失败/跳过/ADB断线，开发证书不变。发布打包另修正 ZIP 版本根目录与扁平目录原生 SHA 清单各一项，9 项宿主回归通过。

首轮18项中合成 WebView 等待超时1项，及 UI fixture 未等异步回调/小窗遮挡，均保留为失败尝试；补阶段诊断、正确等待/关测试小窗后最终19项通过。原生测试曾引用错误字段/参数次序导致编译失败，修正后完整构建通过。没有把跳过或主机超时计为通过。当前真实账号、真机、完整 Linux 环境覆盖/弱网首次安装、未来更高 Release 系统授权安装链仍待验；API Key 保存继续暂停。

v0.1.1收尾98个本地链接、Git候选/索引各278文件、源码/实际APK公开扫描0发现；外部SHA及独立解压交接通过（277指纹文件、ZIP280条目）。曾遇package service未就绪、SDK环境/检查参数与SHA工作目录误用，等服务就绪并修正调用后通过；合成userinfo等价改写消除误报，没有放宽校验。OAuth辅助调查仅为公开协议静态核对，Node路径失败不计独立通过。产物和数据边界见[应用内更新](APP-UPDATES.md)、[发布说明](RELEASE.md)。以下全部是前轮/历史范围，不计为本轮新跑。

## 文档维护验证：提交前清理交接（2026-11-03，历史）

- 仅修改工作规则、发布/接手文档和交接引用，清理 4 份已被替代的过程记录；未修改产品代码。
- 新跑 `python3 scripts/test-publication.py` 19 项、`python3 scripts/test-agent-workflow.py` 12 项，均通过；本地 Markdown 链接检查通过。该检查不自动判定自然语言交接是否过期。
- 没有重跑 JVM、lint、AndroidTest 或设备任务；此前发布咨询已成功构建标准 modern unsigned APK（`com.tanuki.modern`、仅 ARM64、非 debuggable、未签名），不是本次清理的新构建，也不是可直接安装的正式包。
- 保留以下修复轮与前轮的原始验证范围；交接印章/源码包最新校验结果见[当前状态](AGENT-STATE.md)。没有新的 Git 提交/推送、tag 或 Release。

## 产品修复验证：DSH 解析与弱网恢复（2026-11-03）

| 范围 | 实际结果 | 边界 |
|---|---|---|
| 预置官方依赖锁 | 686 个非根记录均为官方 HTTPS/SHA-512，文件摘要与 APK 固定值一致 | 从官方 registry 新生成的公开元数据，不含依赖实现，不等于发布者签名 |
| 产品依赖图脚本宿主验证 | 模拟 Linux/ARM64/glibc、Node 24.22.2/26.13.0/22.23.4，各选中 560 个归档且不改锁；3 个错误来源/入口校验/缺失必需依赖样本被拒绝 | 使用宿主 npm 11.19.0；不是 Android、PRoot、下载或实际安装测试 |
| JVM | 64 项通过，失败/错误/跳过均 0 | 新增锁缓存、默认预置锁、隔离/损坏/符号链接/失败重试与命令到期/外部取消回归；不代表手机性能 |
| Android 编译/组装 | modern debug、开发签名 release、debug AndroidTest 均成功 | 单一 init/flavor 串行 Gradle，完整构建/JVM/双 lint 4m7s；更新声明后再次组装，不安装 |
| lint | debug/release 各 0 错误、49 警告、1 提示 | 非零警告，不能当全量产品验收 |
| APK 身份与签名 | debug/release 均为 `com.tanuki.modern.workspacecheck`、Tanuki、仅 ARM64；debug=true/release=false；AndroidTest target 同目标；apksigner verify 成功 | 继续使用原开发证书，不是正式签名；未覆盖安装 |
| 公开边界与宿主回归 | Git 工作树/索引扫描 0 发现；19 项上传脱敏、12 项交接/保护回归均通过；两 APK 与重建原生对应源码归档各 0 发现 | 规则有范围限制，不扫描用户数据、真实凭据或设备 |
| 源码/迁移 | 258 个参与交接指纹的公开文件；源码 ZIP 261 个文件（含两份原生附件），0 发现；源码/双 APK/原生外部清单全部匹配；独立目录交接/边界/宿主锁/19+12 项回归通过 | 不依赖原工程路径/Git/私有缓存；结果回填后再次打包核对，不计为 Android 设备通过 |

```bash
python3 scripts/test-dsh-lock.py
source scripts/env.sh
export ANDROID_SDK_ROOT="$TANUKI_SDK"
./gradlew --no-daemon -PtestBuildType=debug -PdevelopmentReleaseSigning=true \
  -I scripts/workspace-verification.init.gradle \
  :app:assembleModernDebug :app:assembleModernRelease :app:assembleModernDebugAndroidTest \
  :app:testModernDebugUnitTest :app:lintModernDebug :app:lintModernRelease
python3 scripts/test-publication.py
python3 scripts/test-agent-workflow.py
python3 scripts/check-publication.py --git
```

- 最终 debug APK SHA-256：`f98d770eb3d0ad51b302e0deab8c456a22fd5f1d92beb0371b26c03cf551c0c5`。
- 最终 release APK SHA-256：`360f4b2408ccc31712e72cc291153f6b2150adee2ab1d38112ee562f7e211d77`。
- 开发证书 SHA-256 继续为 `371dc301e7bb4d5cb2840510855f191eeb2f1ce4d058618361419409654cd84f`。
- 初次 JVM 执行 64 项中有 1 项测试断言失败：协程栈恢复可复制 `CancellationException`，不能要求对象引用相同；已改为检查取消消息与只调用一次，随后 64 项全部通过。没有将首次失败计为通过。
- 仪器回归已补「已安装/缓存锁复用、无元数据解析、现有程序不变」断言，但本次**仅编译**。默认版确实可跳过元数据解析已由产品逻辑/宿主依赖图和 JVM 回归核对；手机弱网提速幅度、完整候选安装、超时后重试和覆盖升级尚未设备验证。
- 没有延长原请求/总命令超时、绕过完整性、删除已安装组件或用户数据，也没有安装、签名迁移、发布、提交或推送。设备待验还包括实际npm缓存写入、同版本修复/其他版本更新、归档中断重试、清缓存后预置锁恢复、实际Node/npm切换重检及无可信锁版本解析回退；不承诺弱网提速倍数。

## 前一轮构建/迁移记录（2026-11-02，非本轮重跑）

以下保留前轮的实际范围与散列供对照，**旧 APK 散列不能用于本轮修复包**。

| 范围 | 实际结果 | 边界 |
|---|---|---|
| 上传与脱敏宿主回归 | 19项通过 | 工作树/索引、凭据/身份、品牌/路径、归档预算/元数据、Kotlin字符串与必要署名等合成样本 |
| 工程宿主回归 | 12项通过 | 交接/迁移、模拟设备保护、debug/release包名隔离、正式密钥相对路径/符号链接边界；不调用真实ADB/Gradle或密钥 |
| Android编译/组装 | modern debug、release、debug AndroidTest均成功 | 单一Gradle流程，4m7s；没有执行设备instrumentation |
| JVM | 45项通过，失败/错误/跳过均0 | `testModernDebugUnitTest`；不代表Android/guest/模型实测 |
| lint debug及release | 各0错误、49警告、1提示 | 存量警告保留，非零警告，不是全量产品验收 |
| APK | 两包标签Tanuki、目标均`com.tanuki.modern.workspacecheck`、仅ARM64；debug=true、release=false | `aapt dump badging`核验；test target同目标；不安装 |
| 签名/公开边界 | 两包apksigner verify成功，使用同一开发证书；各自递归扫描0发现 | 不是正式证书；自动规则有范围限制 |
| 原生源码 | 对应源码归档生成成功，公开边界/归档扫描0发现 | 固定PRoot提交与talloc SHA256；不等于设备运行通过 |
| 默认release签名 | signingReport：modern/compat的Config/Store均null | 未传开发签名属性，默认unsigned；compat未编译 |
| 源码/迁移 | 251个源码文件（含2个对应源码附件），ZIP递归扫描0发现，外部散列均匹配；独立目录交接/边界及19+12项宿主回归通过 | 不依赖原工程路径/Git/私有缓存；TAR元数据已归一化 |

```bash
python3 scripts/test-agent-workflow.py
python3 scripts/test-publication.py
source scripts/env.sh
./gradlew -PtestBuildType=debug -PdevelopmentReleaseSigning=true \
  -I scripts/workspace-verification.init.gradle \
  :app:assembleModernDebug :app:assembleModernRelease :app:assembleModernDebugAndroidTest \
  :app:testModernDebugUnitTest :app:lintModernDebug :app:lintModernRelease
```

`AppBrandingTest.applicationAndLauncherAreNamedTanuki`检查应用标识、应用类、资源和Launcher标签；本轮仅编译，不在设备执行。release开发签名仅由显式属性启用，默认release留给维护者正式签名；[RELEASE](RELEASE.md)列明方式。

开发证书SHA256：`371dc301e7bb4d5cb2840510855f191eeb2f1ce4d058618361419409654cd84f`。

- debug APK SHA256：`bb9184a657b94f3718353a68c3c68a189639ca2637be3ddc082045584829c7e8`
- release APK SHA256：`df929e811749c10dd0274c4f2485a710d6eccf61a8a20b6a2cc63d18c453ad9c`

Git与源码边界扫描通过，249个Git候选、0索引、0发现；公开归档/迁移范围保留于本节表格，原已完成交接在0.1.2提交前清理。原生依赖官网首次下载HTTP504，使用官方FTP镜像且SHA256完全匹配后重试成功；未放宽TLS/哈希。

发布扫描不覆盖所有自定义秘密、姓名、图片像素或Git历史，第三方必要许可署名保留。原始日志不上传，不以本机缓存作为交接依据。

## 参考范围（不是本轮设备结果）

既有版本曾通过14类54项API34 ARM64/WebView159设备回归，覆盖浏览器权限、多页、整页尾部截图、缩放/小窗、文件/容量、安装/基础工具和rootfs恢复；使用合成页面，不调用模型或真实微信登录。本轮受影响范围未设备重跑，不能将参考数字算作当前APK通过。

原生运行路线曾验证普通UID、non-debuggable环境下的Ubuntu/Node/PTY/HTTP/DNS/HTTPS及官方DSH；不是跨设备、完整模型任务或安全沙箱证明。

## 未测与限制

- 本轮未执行长期生命周期、真实guest文件/进程所有权与真实Skill/模型任务；桌面标识、通知停止和浏览器/文件的合成范围以本版首节为准。
- 本轮已做合成整页尾部像素、AI缩放和小窗回归；跨机型/旋转、既有退出全屏断言仍须单独验收，不能只查DOM。
- 真实模型/微信登录、HyperOS、长期后台、跨WebView/Android、16KB page-size、全部更新/恢复和正式签名未全面验收。
- 快照仍拒绝超出既有白名单的绝对软链接，包括只读工具/下载挂载；未扩展该安全范围或实测相应场景。
- 当前登录跳转仅合成回归已验证，真实账号仍未验收；API Key 保存继续暂停。跳过、超时、失败或 ADB 断开不计通过。

设备测试先确认专用数据、无apt/dpkg/真实任务并明确选择设备；本轮对同包同证书APK覆盖安装，不卸载、清数据或恢复快照。依赖完整运行环境的用例仍需另满足前置条件，见[上手指南](AGENT-QUICKSTART.md)。
