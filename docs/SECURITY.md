# 安全与来源边界

模型使用 API，必要上下文会发送给所配置的模型提供商。DSH、项目文件、Shell 与依赖安装在 Android 本机执行。不能宣传完全离线或代码绝不离开设备。

启动链是 APK 中的 Android ELF supervisor → Bionic PRoot → APK 中的 PRoot ELF loader → Ubuntu glibc 程序。下载文件不是简单 chmod 后直接由 Android execve。实际 targetSdk 34、非 debuggable、SELinux Enforcing 下的启动结果见测试报告；不要求 Root、Termux App、关闭 SELinux 或修改系统镜像。

PRoot 不隔离恶意插件与 App 自己的数据，也不提供恶意代码执行的完整边界。官方 DSH 默认权限与审批不被外壳关闭。所选版本的 Linux 沙箱链为 bwrap / Landlock，本模拟器实际返回 `LANDLOCK_ENFORCEMENT=unusable`，首页与诊断保留此结果。不能把程序成功启动当作沙箱成功；不使用假 runner 冒充隔离，也不静默切换 danger-full-access。

固定 Ubuntu 与 Node 的 SHA-256 编译进 APK，首次下载必须匹配；对应上游 HTTPS 地址和选定时的校验值记录在组件清单。选定 DSH 包的 SHA-512 integrity 与 npm 产生的 lockfile 比对，依赖由 npm 校验并保留 lockfile。更新版 integrity 来自本次官方 npm 元数据，通过 TLS 获取并存储，不等于独立的发布者签名认证。当前 Android 安装器未内置 Ubuntu / Node 的 GPG 验签器，也未验证 npm registry 签名或 provenance；不得将这些哈希宣传为已经完成发布者签名验证。离线发布 APK 时，维护者应核验上游签名并审核固定校验值，再使用可信签名密钥签发 APK。

下载只允许固定上游 HTTPS host，不自动跟随任意重定向；条件续传检查 Range，完整文件再验哈希。归档只在空暂存目录解包，限定文件数和总大小；拒绝绝对条目与路径穿越。普通文件先写入，再构造合法符号链接；绝对客体链接重写为根目录内相对链接。Android 禁止硬链接时记录并由 PRoot 重建，不把尚未完成的环境标记为正常。仅保留普通权限位，不在宿主创建设备节点或 setuid 权限。

管理 WebView 不允许 file:// 访问，没有命令 JavaScript Bridge，不忽略 TLS 错误。管理网页只允许当前精确 origin；新的本机预览端口不被当作管理 origin。第三方 Cookie 关闭；受支持的外部 HTTP(S) 链接在独立浏览器中新建页面，不在管理 WebView 中加载；管理 origin/端口与带管理 token 的外链不转交。官方 HTML 预览的源码声明使用不带 allow-same-origin 的 sandbox iframe；运行时仍需实际验证，不因代码声明就认定所有预览已隔离。当前严格的资源来源策略可能限制外部资源，详见测试结果。

独立浏览器按用户最新选择默认授权应用当前连接，只向持有本机 capability 的工具开放受控操作，不是任意客户端可自行授权。点击网页会先暂停/使操作票据失效，原生确认收回后持久保留选择；切到后台不会自行撤销。前台通知可收回或停止浏览器及下载。浏览器使用独立 WebView profile，与管理页的 Cookie/存储隔离；这是连接级能力，不是模型身份认证或恶意同 UID 代码沙箱。业务提交/付款等仍需另行确认。WebView 子资源回调不保证覆盖每一跳重定向，不能宣称已建立完整网络/SSRF隔离。具体边界与测试见 [浏览器工作区](BROWSER-WORKSPACE.md)。

官方 Blob 导出通过文件专用 WebMessageListener。仅在精确管理 origin 注册；回调验证 sourceOrigin、主 frame、待处理请求与随机一次性 nonce；拒绝无请求和重放消息，传输上限 8MiB，最后经 SAF 确认保存。桥没有 shell、任意文件读或动态执行能力，其他端口不注入。该通道可在设置关闭；专项测试已验证实际 UTF-8 文件字节、伪造 nonce 拒绝和另一个端口没有对象。

官方启动 token 仅保留在当前进程内存，不写入日志或 state 元数据。签名 cookie 由 WebView 与官方服务处理；启动 URL 在进入网页后由官方重定向清理。运行日志过滤已知 Key、token、Bearer 等格式，但模式过滤不能保证识别任意插件输出的所有敏感信息，因此默认不公开导出任意 DSH 错误日志。系统 logcat 只记录运行身份和退出码，测试输出仅用于专用无密钥环境。

API Key 通过官方 Web UI 配置，不编译入 APK 或写进仓库。账号和会话保存在 App 私有空间；关闭 Android 自动备份，并通过 dataExtractionRules 排除云备份和设备迁移。内部快照可能包含凭据，不能直接明文分享。SAF 项目导出不含 userdata，排除常见凭据文件；源文件仍需用户自行检查。没有请求全盘存储访问权限。

停止只作用于本 App 记录的 Java Process 与相应进程组，发送信号前检查 /proc starttime，防止 PID 重用；不使用 killall node。supervisor 设置独立 session 和 parent-death 信号，PRoot 额外启用 PTRACE_O_EXITKILL，避免 tracer 消失后客体失去路径转换仍继续执行。进程退出后不自动重放用户操作。系统强制停止后的恢复以实际文件和新进程检查为准。
