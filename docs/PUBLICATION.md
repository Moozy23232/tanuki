# Git 上传与脱敏

## 只上传源码

保留源码、Gradle Wrapper、固定构建脚本/补丁、许可和必要文档；本轮新增/修改的第一方注释使用中文，第三方版权/许可证保留原文。`.gitignore` 排除构建/原生ELF/安装包/归档、缓存/依赖、本机IDE和SDK配置、密钥/签名/账号配置、用户项目/会话/快照、原始日志/抓包/数据库。`docs/evidence/` 不上传，公开测试结论写 [TEST-REPORT](TEST-REPORT.md) 和当前交接。

忽略不等于脱敏：**已跟踪文件仍会提交，历史也不会消失**。不要自动 `git add .`、提交或推送；先检查实际候选和暂存内容。

## 本地检查

```bash
python3 scripts/test-agent-workflow.py
python3 scripts/test-publication.py
python3 scripts/check-publication.py --git
python3 scripts/check-handoff.py

git status --short
git diff --cached --stat
# 在本机人工复查 git diff --cached，勿把潜在秘密贴到聊天/公开日志。
```

`check-publication.py --git` 分别检查索引blob和工作树候选，已暂存内容即使在工作树被清理也不会漏扫；不打开被忽略的凭据，已跟踪文件若违反忽略/公开边界只报告、不代删。检查本机身份路径、常见API/云/平台凭据格式、非示例邮箱和文档链接；归档检查包含ZIP/APK/TAR成员、TAR身份元数据与嵌套累计预算。输出文件位置也先脱敏，**不输出匹配值**。必要第三方许可/固定上游源码署名保留，第一方文件不作整包豁免。

本机工程已删除旧归档/安装包/参考资料、生成缓存、本机配置和原始日志；最新交付独立放在被忽略的artifacts。JDK、AVD数据/控制文件和另一个进行中任务的暂存保留且被忽略，不因整理源码而wipe设备或杀进程。

这不是全能Secret Scanner：不能识别所有姓名、任意插件输出、自定义凭据格式、所有图片像素或Git历史。历史截图/原始诊断已从docs清理；新增图片/附件必须人工检查像素、EXIF和内容。示例只能使用合成值/保留域名。

## 身份与历史

提交前自行设置允许公开的昵称和GitHub隐私邮箱，例如：

```bash
git config --local user.name "Public contributor"
git config --local user.email "<GitHub提供的隐私邮箱>"
```

本地初始化/修改作者设置不能改写已提交历史。若文件已被跟踪，确认无泄露后再有选择地从索引移除；若秘密曾公开，先吊销/轮换，再另行授权清理历史。检查脚本不会重写历史、修改作者配置或上传文件。

## 源码归档与二进制

```bash
python3 scripts/check-publication.py
python3 scripts/package-source.py
python3 scripts/check-publication.py --archive artifacts/Tanuki-0.1.0-dev-source.zip
(cd artifacts && shasum -a 256 -c SOURCE-SHA256SUMS)
```

Git检出不依赖本机构建附件。源码ZIP按明确目录清单生成；可选原生对应源码附件齐全时一并包含，否则在分发原生二进制前先准备对应源码并审核GPL等义务，见 [第三方声明](../THIRD_PARTY_NOTICES) 和 [发布说明](RELEASE.md)。只保留 `scripts/package-source.py` 这一个打包入口，删除无独立用途的旧兼容脚本。

修改任何公开文件后都要更新交接/指纹并重打源码包，旧ZIP不能当作新版本。APK、用户数据和原始日志不提交Git；需要正式Release时另行验收、签名与授权发布。
