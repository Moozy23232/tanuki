# 交接：浏览器恢复浮动透显

- 时间：2026-11-04 17:50 CST开始；状态：实现/专项验证完成，用户已授权本地提交，未授权推送。
- 分支：`fix/browser-floating-navigation`，从最新main/4509972创建，源码仍0.1.5/code7。
- 前一记录：[0.1.5发布（已归档）](../TEST-REPORT.md)。

## 用户目标与范围

修复后续提交给浏览器加回底部留白的问题。原生前进/后退栏已在顶部；区别于网页自己固定在底部的输入框/按钮。中央人/AI按钮另行实施，不混入此修复。

## 当前状态

浏览器默认透显；原生通用设置提供显式「避让网页底部控件」，开启才缩短网页视口，偏好保存。网页确有固定底栏时需用户开启避让或使用全屏，不宣称默认覆盖模式下所有底部控件都可直接点击。DSH聊天输入区保持main原样。

## 本次改动

- AgentBrowserPage：底部留白受显示偏好控制，默认关闭；复用原生ListItem/Switch，不改顶部工具、授权或WebView实例。
- BrowserLibrary：保存一个显示布尔值，旧元数据默认关闭，不改变权限/下载/标签边界。
- BrowserWorkspacePresentationTest：恢复导航后方像素断言；避让通过真实UI开启/关闭，原固定控件真实触摸断言保留，并增加同WebView/timeOrigin、开关关闭恢复高度与事件保留检查。
- BrowserLibraryTest：默认值与重新读取偏好回归；入口文档同步。没有增加依赖、抓取网页或注入DOM布局启发式。

## 验证记录

- 新透显断言在main原布局先失败。尝试把避让挪到不裁切的原生宿主：固定控件2项通过，但导航后方像素仍失败；已完全撤回该实验，不当成修复。初次新设置引用了不存在的图标导致编译失败，改用已有图标，未增加依赖。
- 实际执行modern/compat debug、modern AndroidTest、testModernDebugUnitTest、lintModernDebug：成功；**97 JVM无失败/跳过，modern lint0错误/71警告/1提示**。独立init仅将公开验证脚本debug后缀改为 `.uipolish.workspacecheck`。
- 核验包名/target及无运行任务，在Tanuki_UI_API36/emulator-5554只覆盖独立包。逐方法 `am instrument -w -r -e class <类#方法>`：**12项全过，97.5秒**，无失败/跳过/超时：透显、可选避让、偏好持久化、DSH固定控件/全屏、15页生命周期、地址真实触摸/暗色200%、撤权可见/后台重建、整页及AI缩放/小窗、私有登录保护。
- 避让测试补postVisualStateCallback，避免UI空闲但网页帧未完成就截图；另重跑该项通过12.7秒，已核对普通透显及避让后固定控件真实画面。前轮22/57项不累计本轮。
- 实测产品APK固定留存，apksigner、身份/code7、SHA及公开扫描通过。正常Studio包另行恢复且未安装，不冒充正式release。

## 未完成与暂停项

实现无待修项，等用户手机复验；真机/低API/真实模型/大规模标签压力未测，既有退出全屏systemBars断言未修复。中心控制按钮与API Key保存暂不展开。

## 下一步

先按用户授权提交当前悬浮修复；新增反馈「进入全屏再退出，顶部多一行空白」另建记录、补回归后修复，不把当前12项当作该问题已验证。未授权推送。

## 约束与风险

不清数据/卸载或覆盖正常安装，不改全局整图绘制设置。默认浮动与网页固定底栏存在实际覆盖取舍，使用显式开关而非对所有网页强制留白；DSH保护未回滚。旧手机截图在微信受保护目录中，未绕过权限读取。

## 产物与校验

新包：artifacts/builds/browser-floating-fix/Tanuki-browser-floating-fixed-debug.apk（独立包、0.1.5/7、ARM64、debug），可覆盖旧独立对照安装；SHA256SUMS及简短README同目录。当前匹配源码候选为artifacts/sources/Tanuki-0.1.5-source.zip；旧对照APK仍在browser-floating-check，源码对应c95fa9f，不能混作本修复。

本轮不动main/旧分支或发布目录，不创建tag/Release。对照包任务的未提交文档已具名stash保留，无产品代码丢弃；提交前审阅并删除已完成的1632发布过程记录：APK/SHA、97/57/49验证、失败及未验边界已完整保留于TEST-REPORT和发布专题，不依赖该过程记录或Git历史。
