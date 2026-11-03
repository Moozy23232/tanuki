# 签名与 GitHub Release

本工程未公开发布。`debug`、显式启用开发签名的 `release` 与 `probe` 使用本机开发证书；`release/probe` 为non-debuggable，开发证书只用于本地验证。正式发布应使用由用户持有、在仓库外备份的独立密钥。开发签名 APK 不能直接被不同正式证书的 APK 覆盖安装；不能为切换签名静默卸载 App，先处理数据导出与迁移。

## 本地 debug / release

两份可安装验证包使用开发证书，目标均为 `com.tanuki.modern.workspacecheck`；release关闭调试标志，不等于正式签名发布，不启用代码压缩。

```bash
source scripts/env.sh
./gradlew -PtestBuildType=debug -PdevelopmentReleaseSigning=true \
  -I scripts/workspace-verification.init.gradle \
  :app:assembleModernDebug :app:assembleModernRelease
```

输出分别为 `app/build/outputs/apk/modern/debug/app-modern-debug.apk` 和 `app/build/outputs/apk/modern/release/app-modern-release.apk`。不传 `developmentReleaseSigning=true` 时，release保持未签名；不能把开发签名当正式证书。脚本变量统一使用 `TANUKI_` 前缀，见 `scripts/env.sh`。

## 网页发布现有测试包

现有 `artifacts/Tanuki-0.1.0-dev-workspacecheck-release.apk` 是 **release 构建类型、开发证书签名的测试包**，并非正式签名。若只是提供当前版本下载，先明确以测试版发布，建议勾选 GitHub 的 **Pre-release**：

1. 按 [上传检查](PUBLICATION.md) 清理过期交接、核对实际暂存与归档；将待发布源码提交并推送到目标仓库，重打源码 ZIP/外部清单。tag 应指向与 APK 对应的源码，不上传密钥、日志或整个 `artifacts/` 目录。
2. 在目标 GitHub 仓库进入 **Releases → Draft a new release**，选择对应提交（通常为已核对的 `main`），创建未使用的 tag，例如 `v0.1.0-dev.1`，填写标题与下文要求的版本说明。tag 示例不表示 APK 内的 `versionName=0.1.0-dev` / `versionCode=1` 已改变。
3. 逐项附加已核验的 release APK 与 `RELEASE-SHA256SUMS`、完整源码 ZIP 与 `SOURCE-SHA256SUMS`、原生对应源码包与 `native-source-SHA256SUMS`。GitHub 自动生成的 Source code 压缩包不保证包含原生对应源码，不能用它替代必要附件。
4. 勾选 **Set as a pre-release**，复核目标仓库的可见性、版本说明和附件，确认授权范围后点击 **Publish release**。不要上传 unsigned APK；未签名包不能直接安装。

网页操作不需要本机安装 `gh`。上述是手工步骤，不是已执行的发布，也不意味着允许 Agent 自行创建 tag、上传或更改仓库可见性。

当前测试包名为 `com.tanuki.modern.workspacecheck`，只能与相同包名/证书且系统允许的安装覆盖更新，更新前先结束安装/真实任务。标准正式签名脚本没有验证用 init 脚本，生成的是 `com.tanuki.modern`：它与当前测试包是不同安装，不能共享现有私有 Ubuntu/Node/DSH 或自动迁移数据。不要为此卸载旧 App；完整账号/会话便携迁移仍有[限制](UPDATE-RECOVERY.md)。

## 正式签名

正式发布前先决定长期包名、版本号（`versionCode` 单调递增）、证书和升级/迁移策略，完成相应构建与验收。当前脚本的产物名固定含 `0.1.0-dev`，若变更版本，需同步核对签名/源码打包脚本和附件命名，不能只改 tag 就宣称 APK 已升级。

生成密钥应由用户在可信本机交互完成，例如：

```bash
# 示例，不由 Agent 自动生成或提交；不要把密码放到命令行或 Git。
keytool -genkeypair -storetype PKCS12 -keystore /用户指定的仓库外目录/Tanuki.p12 \
  -alias tanuki -keyalg RSA -keysize 3072 -validity 10000
```

签名步骤已经写成 [scripts/sign-release.sh](../scripts/sign-release.sh)。需要设置 `TANUKI_KEYSTORE`、`TANUKI_STORE_PASSWORD`，以及可选的 `TANUKI_SIGNING_ALIAS`、`TANUKI_KEY_PASSWORD`。密码只通过命名环境变量传给 apksigner，不写入源码、Gradle 配置或日志。脚本规范化密钥路径并拒绝仓库内目标（含相对路径及符号链接），显式构建unsigned release、签名、verify并计算SHA-256；不会上传文件。

原生组件采用 GPL/LGPL，APK 发布必须同时提供完整对应源码、修改补丁、构建脚本和许可。`scripts/package-native-sources.sh` 在本地生成原生对应源码包，包含精确 PRoot 工作树、必要公共 Git 对象、talloc 原始源码、兼容头生成脚本与 supervisor；不包含用户 Git 配置或 reflog、签名密钥、SDK、JDK、API Key 或设备数据。所有 Android/Kotlin 封装源码也应随公开源码仓库或完整源码包提供。

发布说明应包含：

- 应用版本、APK 文件名、ARM64、min/target/compile SDK、签名证书公开指纹、APK SHA-256。
- Ubuntu、Node/npm、pnpm、DSH、PRoot 的实际版本、来源、校验与许可。
- 安装所需空间、首次网络下载与模型 API 的数据发送边界。
- 实测设备与构建类型；模拟器、真机、模型测试分别列明。
- 旧 WebView 标准 API 兼容层、Android Linux 沙箱缺失、后台限制及回退边界。
- 与 APK 对应的完整源码和构建方式；DSH 独立更新的版本兼容性不作无条件保证。

创建 GitHub repository / Release、设置可见性和上传 APK 之前，需要用户明确授权发布位置与可见范围。源码已推送不等于 Release 已发布，也不等于允许上传二进制；本轮仅说明流程，没有自动执行发布。
