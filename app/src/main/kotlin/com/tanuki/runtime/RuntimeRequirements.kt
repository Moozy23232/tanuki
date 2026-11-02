package com.tanuki.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update

// 固定白名单的轻量 DSH 开发工具；仅补缺失项，不安装服务端/桌面或后台守护进程。
// 调用方持有 manager.mutation；Node/pnpm 探测由外部既有管线负责，此处不涉及。
internal class RuntimeRequirements(private val manager: RuntimeManager) {
    private val ids = packages

    suspend fun inspect(tools: Boolean = true): List<String> = withContext(Dispatchers.IO) {
        val libChecks = """
            [ -r /usr/lib/aarch64-linux-gnu/libatomic.so.1 ] || echo libatomic1
            [ -r /usr/lib/aarch64-linux-gnu/libstdc++.so.6 ] || echo libstdc++6
        """.trimIndent()
        val toolChecks = """
            /usr/bin/git --version >/dev/null 2>&1 || echo git
            /usr/bin/curl -q --version >/dev/null 2>&1 || echo curl
            /usr/bin/python3 -I -c 'import ssl; import sys' >/dev/null 2>&1 || echo python3
            /usr/bin/python3 -I -c 'import venv, ensurepip; ensurepip.version()' >/dev/null 2>&1 || echo python3-venv
            /usr/bin/ssh -V >/dev/null 2>&1 || echo openssh-client
            /usr/bin/openssl version >/dev/null 2>&1 || echo openssl
            /usr/bin/rg --version >/dev/null 2>&1 || echo ripgrep
            /usr/bin/jq --version >/dev/null 2>&1 || echo jq
            /usr/bin/zip -v >/dev/null 2>&1 || echo zip
            /usr/bin/unzip -v >/dev/null 2>&1 || echo unzip
            /usr/bin/rsync --version >/dev/null 2>&1 || echo rsync
            /usr/bin/file --version >/dev/null 2>&1 || echo file
            /usr/bin/less --version >/dev/null 2>&1 || echo less
            /usr/bin/patch --version >/dev/null 2>&1 || echo patch
            /usr/bin/make --version >/dev/null 2>&1 || echo make
            /usr/bin/pkg-config --version >/dev/null 2>&1 || echo pkg-config
            /usr/bin/xz --version >/dev/null 2>&1 || echo xz-utils
            [ -x /usr/bin/xdg-user-dir ] || echo xdg-user-dirs
            [ -s /etc/ssl/certs/ca-certificates.crt ] || echo ca-certificates
        """.trimIndent()
        // These are Linux guest paths. Host /data/user/... paths do not name files inside PRoot.
        val script = (if (tools) toolChecks + "\n" else "") + libChecks
        if (tools) manager.requiredTools.update { it.copy(checked=false) }
        val result = manager.launcher.run(manager.paths.root, listOf("/bin/sh", "-c", script), 60000)
        check(result.exitCode == 0) { "必要组件自检失败：" + Redact.text(result.stderr.ifBlank { "exit=${result.exitCode}" }).takeLast(1200) }
        val missing = result.stdout.lines().map { it.trim() }.filter { it in ids }.distinct()
        if (tools) manager.publishRequiredTools(missing)
        missing
    }

    suspend fun ensure(tools: Boolean = true) {
        manager.phase("检测必要组件", EnvironmentState.VERIFYING)
        var missing = inspect(tools)
        if (missing.isEmpty()) { manager.event("必要组件检测通过"); return }
        manager.event("缺少必要组件：" + missing.joinToString("、"))
        manager.configureMinimalUbuntu()
        manager.phase("补齐必要组件：" + missing.joinToString("、"), EnvironmentState.INSTALLING)
        val configure = manager.launcher.run(manager.paths.root, listOf("/usr/bin/dpkg", "--configure", "-a"), 600000)
        val onStatus: (String) -> Unit = { line ->
            aptProgress(line)?.let { progress ->
                manager.state.update { it.copy(stage=progress.first,phasePercent=progress.second,download=null) }
            }
        }
        if (configure.exitCode != 0) {
            val repair=manager.launcher.run(manager.paths.root, listOf("/usr/bin/apt-get", "-o", "APT::Status-Fd=2", "install", "--fix-broken", "-y"), 600000,onLine=onStatus)
            check(repair.exitCode==0) { "依赖配置修复失败："+Redact.text(repair.stderr).takeLast(1200) }
            val retried=manager.launcher.run(manager.paths.root, listOf("/usr/bin/dpkg", "--configure", "-a"), 600000)
            check(retried.exitCode == 0) { "dpkg 配置修复失败：" + Redact.text(retried.stderr).takeLast(1200) }
        }
        check(manager.launcher.run(manager.paths.root, listOf("/usr/bin/apt-get", "-o", "APT::Status-Fd=2", "update"), 900000,onLine=onStatus).exitCode == 0) { "apt update 失败，已保留原环境" }
        val pkgs = missing.joinToString(" ")
        val install = manager.launcher.run(
            manager.paths.root,
            listOf("/usr/bin/apt-get", "-o", "APT::Status-Fd=2", "install", "-y", "--no-install-recommends", "--reinstall", "--") + missing,
            900000,onLine=onStatus
        )
        check(install.exitCode == 0) { "必要组件安装失败 exit=${install.exitCode}：" + Redact.text(install.stderr + "\n" + install.stdout.takeLast(1500)).takeLast(2500) }
        missing = inspect(tools)
        check(missing.isEmpty()) { "补齐后仍缺少：" + missing.joinToString("、") }
        manager.state.update { it.copy(phasePercent=null) }
        manager.event("补齐完成：$pkgs")
    }
    companion object {
        const val PROFILE = 2
        internal val packages = listOf("ca-certificates", "curl", "git", "python3", "python3-venv", "xz-utils",
            "xdg-user-dirs", "libatomic1", "libstdc++6", "openssh-client", "openssl", "ripgrep", "jq", "zip", "unzip",
            "rsync", "file", "less", "patch", "make", "pkg-config")
        internal fun aptProgress(line: String): Pair<String,Int>? {
            val match=Regex("^(dlstatus|pmstatus):[^:]*:([0-9]+(?:\\.[0-9]+)?):.*$").matchEntire(line) ?: return null
            val percent=match.groupValues[2].toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: return null
            return (if(match.groupValues[1]=="dlstatus")"下载系统组件" else "安装系统组件") to percent.toInt()
        }
    }
}
