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

## 正式签名

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

创建 GitHub repository / Release、设置可见性和上传 APK 之前，需要用户明确授权发布位置与可见范围。本会话只准备本地可审查产物。没有自动运行 gh release、push 或公开仓库的命令。
