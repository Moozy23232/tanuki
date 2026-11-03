# 交接：发布 Release 的可执行流程核对

- Agent/会话标识：主 Agent / release-publishing
- 时间：2026-11-03 11:58 CST 开始
- 状态：流程与未签名构建核对完成，正式发布待用户决定；**未获发布授权，未创建 Release/标签**
- 前一记录：源码提交结果保留在[当前状态](../AGENT-STATE.md)，原过程交接按后续清理规则删除；产品验证见[测试现状](../TEST-REPORT.md)。

## 用户目标与范围

- 用户询问「怎么发布这个 release」，属于流程咨询，不是发布授权。
- 本次静态核对现有签名/发布脚本与产物，并运行本地 unsigned 构建，确认从源码到正式签名 APK、再到 GitHub Release 的实际可执行步骤与未决事项。
- 不变更产品代码，不生成或读取正式密钥/密码，不创建 tag、Release 或修改仓库可见性，不上传任何文件。
- 依据 `docs/RELEASE.md`：创建仓库/Release、设置可见性、上传 APK 前必须具备用户的明确授权，本轮未获得。

## 当前状态

- 源码已推送：`adf9d55`（修复）、`b1ef5e3`（交接）。`main` 与 `origin/main` 一致，当时工作树干净。
- **已核实（本轮新跑）**：`./gradlew -PdevelopmentReleaseSigning=false :app:assembleModernRelease` 成功，输出 `app/build/outputs/apk/modern/release/app-modern-release-unsigned.apk`，`aapt` 显示 `package: name='com.tanuki.modern' versionCode='1' versionName='0.1.0-dev'`、仅 `arm64-v8a`、无 `application-debuggable`，`apksigner verify` 返回未签名。因此 `scripts/sign-release.sh` 中写死的 unsigned 路径与实际输出一致，无需修改脚本。
- **正式发布与开发包的关键差异**：标准 modern 包名是 `com.tanuki.modern`；当前交付/已装开发包的 `com.tanuki.modern.workspacecheck` 由 init 脚本追加后缀。正式包是**并列安装**而非覆盖升级，且现有开发包无法与之共享数据；切包名需要另行导出/迁移，不得静默卸载。
- **阻塞/缺口**：环境中 `gh` 未安装、无 `GH_TOKEN`/`GITHUB_TOKEN`；`TANUKI_KEYSTORE`/`TANUKI_STORE_PASSWORD`/`TANUKI_KEY_PASSWORD`/`TANUKI_SIGNING_ALIAS` 均未设置；仓库尚无任何 tag。正式密钥必须由用户在本机交互创建并保存在仓库外，不由 Agent 生成或代管。
- **待决策**：是否保留 `versionName=0.1.0-dev`/`versionCode=1`；若按正式 Release 发布，通常需提升版本号并决定 tag 名（如 `v0.1.0`）。

## 本次改动

| 文件 | 改动与目的 |
|---|---|
| 本记录、`docs/AGENT-STATE.md`、`docs/handoffs/CURRENT.json` | 记录发布流程核对结论、阻塞项与授权边界；未改产品、脚本或发布文档 |
| `artifacts/Tanuki-0.1.0-dev-source.zip`、`SOURCE-SHA256SUMS` | 文档变化后重新生成并核对 |
| `app/build/outputs/apk/modern/release/app-modern-release-unsigned.apk` | 仅为核对文件名/包名/签名状态产生的本地构建产物，不进入交付或 Git |

## 验证记录

| 实际命令 / 操作 | 环境与结果 | 证据与限制 |
|---|---|---|
| 阅读 `docs/RELEASE.md`、`scripts/sign-release.sh`、`app/build.gradle.kts`、`scripts/workspace-verification.init.gradle` | 已完成 | 只读源码与文档 |
| `command -v gh`、Git 凭据/令牌变量存在性、`git tag --list`、`ls artifacts` | `gh` 未安装；`GH_TOKEN`/`GITHUB_TOKEN`/`TANUKI_KEYSTORE` 等均未设置；无 tag；产物齐全 | 只输出存在性布尔值，不回显任何密钥或令牌 |
| `./gradlew --no-daemon -PdevelopmentReleaseSigning=false :app:assembleModernRelease` | 成功；unsigned 文件名为 `app-modern-release-unsigned.apk`；包 `com.tanuki.modern`、仅 ARM64、非 debuggable、`apksigner verify` 判定未签名 | 本地构建，未签名、未安装、未上传；不重跑 JVM/lint，不改变前轮验证结论 |
| 原定本记录独立交接刷新/打包 | 被用户追加的提交前清理要求打断，未以本记录独立执行 | 后续在当前交接统一更新状态/指纹、检查并打包，不冒充此前已执行 |
| 发布、签名、打 tag、上传 | **未执行** | 缺少用户对可见范围/位置的明确授权，且缺少正式密钥与 GitHub 认证 |

## 未完成与暂停项

- 正式签名未执行：用户需先在仓库外创建并备份生产密钥，再通过环境变量提供口令；Agent 不生成、不读取、不写入该密钥。
- GitHub Release 未创建：需用户明确授权仓库可见性（公开/私有）、Release 位置与上传内容；`gh` 未安装，可选网页手动创建。
- 版本号/tag 决策未定；APK 未在真机安装验收，也未做模型/微信登录验证。
- 手机弱网与覆盖升级测试仍属前轮未完成项；登录/API Key 继续用户暂停。

## 下一步

1. 决策版本：如发布 `v0.1.0`，先改 `app/build.gradle.kts` 的 `versionCode`/`versionName`，跑完整构建/JVM/lint 并更新 `docs/TEST-REPORT.md` 与交接。
2. 用户交互创建生产密钥（示例命令在 `docs/RELEASE.md`），保存在仓库外并离线备份；随后设置 `TANUKI_KEYSTORE`、`TANUKI_STORE_PASSWORD`、可选 `TANUKI_SIGNING_ALIAS`/`TANUKI_KEY_PASSWORD`，运行 `bash scripts/sign-release.sh`，核对 `artifacts/release-signature.txt` 与 `artifacts/release-SHA256SUMS`。
3. 用 `apksigner verify --print-certs` 核对正式证书指纹与开发证书 `371dc301e7bb4d5cb2840510855f191eeb2f1ce4d058618361419409654cd84f` 不同；`aapt dump badging` 核对包名/ABI/targetSdk。
4. 打 tag（如 `git tag -a v0.1.0 -m ...` 并推送 tag），按 `docs/RELEASE.md` 清单撰写发布说明；附件至少包含正式签名 APK、`Tanuki-*-source.zip`、`Tanuki-native-corresponding-sources.tar.gz` 与各自 SHA-256 清单（GPL/LGPL 对应源码义务）。
5. 上传前再次运行 `python3 scripts/check-publication.py` 与归档检查，并在获得明确授权后再执行上传；`gh` 未安装时可用 GitHub 网页手工创建 Release。

## 约束与风险

- 开发证书（`371dc301...`）签名的 APK 不能冒充正式签名；若发布测试版必须明确标注与升级限制，切换正式证书后无法直接覆盖已装开发包。
- 不自动卸载、不清数据、不迁移用户数据；换包名/换证书的安装迁移需用户确认并先导出。
- 不把 APK、源码 ZIP、`.build`、凭据或原始日志提交 Git；Release 附件与 Git 提交分开管理。
- 无 `gh` 且无令牌时不得静默安装 CLI 或索取令牌；上传需要用户授权与自有认证方式。

## 产物与校验

- 本轮未生成正式 APK、未创建 tag/Release；`artifacts/` 内开发签名的 workspacecheck 双包、原生对应源码及其 SHA 清单不变；源码 ZIP/清单随后续文档变化重新生成。
- 后续提交前清理任务统一刷新交接与源码包，结果见[当前状态](../AGENT-STATE.md)；网页发布步骤归入[发布说明](../RELEASE.md)。
- 本记录与状态改动尚未提交；是否提交/推送由用户决定，不自动执行。
