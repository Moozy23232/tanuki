# 签名、打包与 GitHub Release

**用户说要发布时，先询问版本号；已经明确的版本不重复问。** 本次用户已确认 **v0.1.3**，使用 `versionName=0.1.3`、`versionCode=5` 与原有 workspacecheck 开发证书。源码已同步用户合并 PR #1 后的 main `ece5c82`；版本代码高于已公开/本地/测试设备的最高4。**用户已追加授权先提交推送本地源码，再准备发布附件；未授权创建 tag 或上传 Release**。

源码已推送不等于 Release 已发布；本地打包、Git 提交/推送、创建 tag、上传附件和更改可见性分别遵守当前授权。

固定收尾顺序：修复与候选APK验证 → 完成所有适用测试 → 版本、测试报告、专题与交接文档一并收尾 → 清理过期记录、检查源码候选包和实际暂存 → 一次提交推送 → 生成/刷新最终6个附件。最终打包按已验证APK的实际SHA核对，纯文档收尾不重复推送或重跑相同产品测试；如APK字节改变则不能沿用候选测试。最终回执与外部校验清单记录交付结果，不为了回填提交自身再制造后续提交。

## 一键准备已确认版本

```bash
source scripts/env.sh
python3 scripts/prepare-app-release.py --version 0.1.3 --version-code 5 --channel workspacecheck
```

脚本先校验参数与当前交接，再串行构建指定版本并核对真实 APK 包名/版本/SDK/证书；输出 **`artifacts/releases/0.1.3/`**（只从此目录取发布附件；分类与历史归档见[取包说明](ARTIFACTS.md)）：

- `Tanuki-0.1.3-workspacecheck-arm64-release.apk`
- `app-update.json`：应用内更新所需清单；不是手写的可信证书来源。
- `Tanuki-0.1.3-source.zip`：完整公开源码，另含必要原生对应源码附件。
- `Tanuki-native-corresponding-sources.tar.gz`、`native-source-SHA256SUMS`
- `RELEASE-SHA256SUMS`：以上附件的实际 SHA-256。

该渠道是 **开发证书签名、non-debuggable 的 release 构建**，并非正式签名。目标为 `com.tanuki.modern.workspacecheck`、ARM64、minSdk26/targetSdk34；保持原开发证书才能覆盖之前同包测试版。生成过程不读取或上传用户数据，不访问设备，不创建 tag/Release。

```bash
(cd artifacts/releases/0.1.3 && shasum -a 256 -c RELEASE-SHA256SUMS)
```

文档/源码变化后也须重打源码 ZIP，并更新发布目录内副本及外部散列，不能沿用打包前的旧 ZIP。`python3 scripts/package-source.py --version 0.1.3` 仅生成源码包，不重新签发 APK；复制后重新核对发布清单。重新打 APK 则应重新执行准备脚本。

## 用户网页发布

1. 按 [上传检查](PUBLICATION.md) 归档必要结论、清理已过期交接、修复引用、检查实际暂存与公开归档，再将对应源码提交推送到目标仓库。tag 应指向与 APK 对应的提交。
2. GitHub 仓库 → **Releases → Draft a new release**，选择该提交，创建未使用的 **`v0.1.3`** tag，标题可为 **Tanuki v0.1.3**。不要只改 tag 而保留 APK 内旧版本。
3. 将上述发布目录的 **全部 6 个附件** 上传到同一个 Release，尤其不能漏掉 `app-update.json`。GitHub 自动生成的 Source code 压缩包不能替代带原生对应源码的完整附件。
4. 本版为 workspacecheck 开发签名测试包，勾选 **Set as a pre-release**；核对版本说明、仓库可见性和附件，再由用户点击 **Publish release**。

应用内检查使用公开 Feed/Release 附件，不调用 GitHub API 或要求 token。测试渠道可以发现公开预发布；正式渠道只跟随 latest 稳定发布。详情及安装边界见 [应用内更新](APP-UPDATES.md)。

## 正式签名渠道

正式发布前先决定长期包名、单调递增版本和证书/迁移策略；使用用户持有并在仓库外备份的独立密钥。不要把开发证书宣传成正式证书，不以卸载旧 App 绕过签名限制。

生成密钥应由用户在可信本机交互完成，例如：

```bash
# 示例，不由 Agent 自动生成或提交；密码不进入命令行或 Git。
keytool -genkeypair -storetype PKCS12 -keystore /用户指定的仓库外目录/Tanuki.p12 \
  -alias tanuki -keyalg RSA -keysize 3072 -validity 10000
```

设置 `TANUKI_KEYSTORE`、`TANUKI_STORE_PASSWORD` 与可选的 `TANUKI_SIGNING_ALIAS` / `TANUKI_KEY_PASSWORD` 后：

```bash
source scripts/env.sh
python3 scripts/prepare-app-release.py --version 用户已确认的版本 --version-code 已确认的递增整数 --channel production
```

[`scripts/sign-release.sh`](../scripts/sign-release.sh) 校验仓库外密钥路径（含相对路径/符号链接），从 `TANUKI_VERSION_NAME` / `TANUKI_VERSION_CODE` 接收明确版本，构建 unsigned、签名、verify、计算 SHA；密码只通过命名环境变量交给 apksigner，不写进源码/Gradle/日志。脚本不上传。

标准 production 包名是 **`com.tanuki.modern`**，不同于当前 `.workspacecheck`，两者不能共享私有 Ubuntu/Node/DSH 或自动迁移数据。同包但不同签名也不能覆盖；完整账号/会话便携迁移仍有 [限制](UPDATE-RECOVERY.md)。本轮未使用正式密钥、未迁移签名、未构建或发布正式渠道。

## 本地 debug 验证

```bash
source scripts/env.sh
./gradlew -PtestBuildType=debug -PdevelopmentReleaseSigning=true \
  -PtanukiVersionName=0.1.3 -PtanukiVersionCode=5 \
  -I scripts/workspace-verification.init.gradle \
  :app:assembleModernDebug :app:assembleModernRelease
```

输出路径由构建类型共用：`app/build/outputs/apk/modern/debug/app-modern-debug.apk`、`app/build/outputs/apk/modern/release/app-modern-release.apk`。必须实际核验包名/证书，不凭文件名安装；不传开发签名属性时 release 默认 unsigned。

## 许可与发布说明

PRoot/talloc 等采用 GPL/LGPL，APK 发布必须同时提供精确对应源码、补丁、构建脚本和许可。[`scripts/package-native-sources.sh`](../scripts/package-native-sources.sh) 仅收集必要公共源码，不收集用户 Git 配置、reflog、签名密钥、SDK/JDK、账号或设备数据。

版本说明应列明应用版本/ARM64/SDK、开发或正式签名、证书公开指纹、APK SHA-256；Ubuntu/Node/npm/pnpm/DSH/PRoot 实际版本与来源许可；空间与首次下载、模型 API 数据边界；模拟器/真机/合成登录/真实模型的分别验证范围；WebView123+与 Android Linux 沙箱/后台限制；完整源码和构建方式。不要把局部测试等同正式产品验收。
