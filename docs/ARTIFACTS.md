# Tanuki 构建产物：从哪里取包

**当前准备发布的是 v0.1.1（versionCode 3）。**

- 安装 APK：`artifacts/releases/0.1.1/Tanuki-0.1.1-workspacecheck-arm64-release.apk`
- GitHub 发布：只上传 `artifacts/releases/0.1.1/` 内全部 **6 个附件**，包含 `app-update.json`、完整源码、原生对应源码及散列。
- 当前是开发证书的 workspacecheck 包，请标记 **Pre-release**；不是正式签名，也没有由 Agent 自动发布。
- 根目录不再放 APK/ZIP。`archive/` 中的旧 0.1.0 和已撤回、未发布的 0.1.2 **不要拿来安装或发布**；不能按最大的版本号猜测当前版本。

## 目录约定

以下树以工程根目录为起点；打开本文件的产物副本时，当前文件夹就是 `artifacts/`。

```text
artifacts/
├── README.md                  # 本说明的生成副本
├── SOURCE-SHA256SUMS           # 最近一次源码打包的校验入口
├── releases/
│   └── 0.1.1/                 # 完整、可直接上传的发布附件（6 个文件）
├── sources/
│   ├── Tanuki-0.1.1-source.zip # 当前工作树源码的打包副本
│   └── native/                # 原生对应源码和同目录校验清单
├── builds/
│   └── production/<版本>/     # 正式签名脚本的中间 APK/签名报告，非完整发布包
└── archive/
    ├── 0.1.0-dev/             # 旧开发 APK、旧源码和原校验文件
    ├── 0.1.1-before-layout/   # 本次整理前的源码/校验记录；不是完整发布包
    ├── 0.1.2-withdrawn/       # 已被用户撤回的候选版本，原目录保留
    └── ARCHIVE-SHA256SUMS      # 归档后可直接校验的相对路径清单
```

`builds/production/` 只在实际正式签名时生成；没有正式密钥时不要创建假包。日常 debug/AndroidTest 构建仍在 `app/build/outputs/apk/`，不冒充发布附件。`sources/` 与 `builds/` 是准备区，不上传整个目录。

## 校验与维护

在工程根目录执行：

```bash
(cd artifacts && shasum -a 256 -c SOURCE-SHA256SUMS)
(cd artifacts/sources/native && shasum -a 256 -c native-source-SHA256SUMS)
(cd artifacts/releases/0.1.1 && shasum -a 256 -c RELEASE-SHA256SUMS && shasum -a 256 -c native-source-SHA256SUMS)
(cd artifacts/archive && shasum -a 256 -c ARCHIVE-SHA256SUMS)
```

历史清单原文保留，里面可能是迁移前的路径或旧打包缺陷；不把它们当当前清单执行。归档完整性使用 `ARCHIVE-SHA256SUMS`，当前发布使用对应版本目录的 `RELEASE-SHA256SUMS`。

- `scripts/package-source.py` 写入 `sources/`，并刷新根目录的 `SOURCE-SHA256SUMS` 与本说明副本。
- `scripts/package-native-sources.sh` 写入 `sources/native/`，校验路径相对此目录。
- `scripts/sign-release.sh` 写入 `builds/production/<版本>/`；`prepare-app-release.py` 核对实际 APK 后，从分类目录收集完整发布附件至 `releases/<版本>/`。
- 发布前先确认版本；同步本说明与当前状态，不在根目录复制“最新 APK”别名，不改旧 APK 文件名假装新版，不根据目录名代替实际包名/签名检查。
- 只改文档/脚本时不重编 APK，但必须重打源码、刷新待交付版本目录的源码副本与散列。已发布的历史附件不要静默改写。
- `artifacts/` 全部是被 Git 忽略的生成产物；本说明的源码为 `docs/ARTIFACTS.md`。历史包本次只归档，没有删除。
