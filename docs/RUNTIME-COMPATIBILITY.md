# Android / Ubuntu 运行兼容性

## 运行路线

普通 Android App 通过参数数组启动 APK `nativeLibraryDir` 中的 supervisor → Bionic PRoot → 静态 ELF loader → Ubuntu ARM64/glibc → Node.js/官方 DSH。不是把下载文件 chmod 后直接交给 Android `execve`，也不依赖 Mac 服务、Root、Termux App 或修改系统镜像。

| 项目 | 当前实现/边界 |
|---|---|
| SDK/ABI | min26、compile/target34、arm64-v8a；compat/target28仅实验对照 |
| 历史运行环境 | Android14/API34 ARM64模拟器、SELinux Enforcing；未覆盖全部内核/真机 |
| 原生构建 | NDK `27.2.12479018`、Android26 Bionic工具链；源码与补丁固定来源 |
| 初始客体 | Ubuntu Base24.04.5 ARM64、glibc2.39；不启动客体内核/systemd/桌面 |
| 初始工具 | Node24.22.2、pnpm11.28.5、官方 `@deepseek-ai/dsh@0.2.1-rc.5`，可选清单见组件指南 |
| DSH | `web --no-open --host 127.0.0.1 --port 0`；只采信官方就绪日志中的精确origin |
| 认证 | 官方启动token→HTTP重定向→签名Cookie，不禁用认证或复制给独立浏览器 |

当前构建和已测/未测以 [TEST-REPORT](TEST-REPORT.md) 为准。PRoot 依赖 ptrace 与宿主内核，Ubuntu 内模拟 root 不等于 Android Root。

## WebView 要求

正常官方页面要求 Chromium123+，并通过离线页实际检测 CSS `subgrid`、`light-dark()`；AndroidX WebKit 升级不等于系统内核升级。优先使用设备厂商/Google Play 的正式 WebView 更新渠道，App 不自动替换用户 provider。历史模拟器使用WebView159，不代表用户手机已更新。

对固定旧 DSH 的 `Promise.withResolvers` / `AbortSignal.any` 补充仅限精确管理origin和受支持版本，不伪造CSS检测、不修改官方npm包；不能把两个JS补充当作完整兼容。WebView必须使用 `MATCH_PARENT` 布局，不能由 `wrap_content` 引发零高度视口。全屏复用当前WebView，改变绘制/进程策略后需实际像素验证。

## 必须保留的实现决策

- Android宿主不直接创建rootfs硬链接。安全解包先准备文件/链接记录，再由PRoot重建；未完成启动验证不写环境就绪标记。
- PRoot link2symlink 的内容存入持久 `link-store`，不跟随DSH临时staging删除；统计/导出/快照校验真实内容。已经丢失的旧内容不能凭空恢复。
- `/tmp` 单独绑定私有 `guest-tmp`，`/dev/shm` 为磁盘目录映射，不具备真正tmpfs语义。
- 子进程创建使用常驻所有者线程，避免创建线程结束导致PDEATHSIG误杀；监督器处理管道退出竞态和后代回收，只终止本App记录且身份匹配的进程。
- rootfs受限权限恢复只作用于自有树，不遍历外部符号链接；apt/dpkg中断恢复不得关闭SELinux或杀死其他应用。
- 默认不安装推荐包/额外语言索引，按需补必需工具、保留版权许可；完整编译器/桌面由任务另行选择。
- bwrap/Landlock必须实际探测。无后端时官方 `workspace-write` 拒绝Shell是权限策略，不是简单的文件权限问题；用户自己决定是否在官方UI选择 `danger-full-access`。

不承诺任意Android版本、16KB page-size、HyperOS后台、桌面/特殊内核插件或任意APK编译能力。API Key/登录不在当前修改范围，非模型诊断不能当作模型任务验收。安全和存储边界见 [SECURITY](SECURITY.md)、[设计](DESIGN.md)。

## 上游参考

- https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission
- https://developer.android.com/develop/ui/views/layout/webapps/webview
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://proot-me.github.io/
- https://github.com/termux/proot/tree/v5.1.107.101
- 所选官方npm包的已发布源码与README；固定来源/许可见 [THIRD_PARTY_NOTICES](../THIRD_PARTY_NOTICES)
