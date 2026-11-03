#!/usr/bin/env python3
"""核对 APK 预置官方锁，并在宿主模拟目标平台执行产品的依赖图检查；不下载、不安装。"""
import argparse
import base64
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import textwrap
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]


def check(value, message):
    if not value:
        raise RuntimeError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--npm-root", type=Path, help="宿主 npm 包目录，只读取其 Arborist 和检查库")
    args = parser.parse_args()
    node = shutil.which("node")
    npm = shutil.which("npm")
    check(node and npm, "需要宿主 Node/npm；不在此脚本中安装工具")
    npm_root = (args.npm_root or Path(npm).resolve().parents[1]).resolve()
    check((npm_root / "node_modules/@npmcli/arborist").is_dir(), "无法找到宿主 npm Arborist")
    source = (ROOT / "app/src/main/kotlin/com/tanuki/runtime/NpmDependencyLocks.kt").read_text()
    bundle = source.split("internal object BundledDshLock", 1)[1]
    constants = {name: re.search(rf'const val {name} = "([^"]+)"', bundle).group(1)
                 for name in ("VERSION", "INTEGRITY", "SHA256")}
    raw = (ROOT / f'app/src/main/assets/dsh-locks/{constants["VERSION"]}.json').read_bytes()
    check(hashlib.sha256(raw).hexdigest() == constants["SHA256"], "APK 预置锁摘要不匹配")
    lock = json.loads(raw)
    check(lock["lockfileVersion"] == 3, "预置锁应为完整的 npm lock v3")
    packages = lock["packages"]
    check(packages[""]["dependencies"] == {"@deepseek-ai/dsh": constants["VERSION"]}, "根依赖不一致")
    top = packages["node_modules/@deepseek-ai/dsh"]
    check(top["version"] == constants["VERSION"] and top["integrity"] == constants["INTEGRITY"], "DSH 发布不一致")
    count = 0
    for name, package in packages.items():
        if not name:
            continue
        check(not package.get("link"), f"锁包含本地链接：{name}")
        url = urlsplit(package["resolved"])
        check(url.scheme == "https" and url.netloc == "registry.npmjs.org" and not url.query
              and not url.fragment and url.path.endswith(".tgz"), f"非官方归档：{name}")
        sri = package["integrity"]
        check(re.fullmatch(r"sha512-[A-Za-z0-9+/]{86}==", sri), f"非 SHA-512 校验：{name}")
        digest = base64.b64decode(sri[7:], validate=True)
        check(len(digest) == 64 and base64.b64encode(digest).decode() == sri[7:], f"不规范的 SHA-512：{name}")
        count += 1
    product = (ROOT / "app/src/main/kotlin/com/tanuki/runtime/NpmPackageDownloads.kt").read_text()
    script = re.search(r'val PLAN_SCRIPT = """(.*?)"""\.trimIndent\(\)', product, re.S).group(1)
    script = textwrap.dedent(script).strip().replace("${'$'}", "$")
    script = script.replace("const base = '/opt/node/lib/node_modules/npm';", f"const base = {json.dumps(str(npm_root))};")
    # 只替换宿主环境声明与 npm 路径；依赖图、完整性及平台过滤使用原产品脚本。
    shim = """Object.defineProperty(process, 'platform', {value:'linux'});
Object.defineProperty(process, 'arch', {value:'arm64'});
Object.defineProperty(process, 'version', {value:process.env.TANUKI_TEST_NODE});
process.report.getReport = () => ({header:{glibcVersionRuntime:'2.39'}});
"""
    with tempfile.TemporaryDirectory(prefix="tanuki-lock-check-") as temporary:
        stage = Path(temporary)
        (stage / "package.json").write_text(json.dumps({"name": "tanuki-dsh-runtime", "version": "1.0.0",
            "private": True, "dependencies": {"@deepseek-ai/dsh": constants["VERSION"]}}))
        plan = stage / "check.cjs"
        plan.write_text(shim + script)
        environment = {"PATH": os.environ.get("PATH", ""), "HOME": temporary, "LANG": "C"}

        def run(data, version="24.22.2"):
            (stage / "package-lock.json").write_text(json.dumps(data))
            environment["TANUKI_TEST_NODE"] = "v" + version
            return subprocess.run([node, str(plan), temporary, constants["VERSION"], constants["INTEGRITY"]],
                                  env=environment, text=True, capture_output=True, timeout=30)

        for version in ("24.22.2", "26.13.0", "22.23.4"):
            result = run(lock, version)
            check(result.returncode == 0, f"模拟 Node {version} 的产品检查失败：{result.stderr}")
            selected = json.loads((stage / ".dsh-plan.json").read_text())["archives"]
            check(bool(selected), "目标平台归档清单为空")
            check(json.loads((stage / "package-lock.json").read_text()) == lock, "平台检查修改了锁")
            print(f"PASS simulated_linux_arm64_glibc node={version} archives={len(selected)} lock_unchanged=true")
        altered = copy.deepcopy(lock)
        altered["packages"]["node_modules/@deepseek-ai/dsh"]["integrity"] = "sha512-" + base64.b64encode(bytes(64)).decode()
        result = run(altered)
        check(result.returncode != 0 and "DSH 发布完整性不一致" in result.stderr, "未拒绝错误的入口校验")
        altered = copy.deepcopy(lock)
        altered["packages"]["node_modules/@deepseek-ai/dsh"]["resolved"] = "https://untrusted.invalid/dsh.tgz"
        result = run(altered)
        check(result.returncode != 0 and "npm 官方地址" in result.stderr, "未拒绝非官方归档")
        altered = copy.deepcopy(lock)
        del altered["packages"]["node_modules/commander"]
        result = run(altered)
        check(result.returncode != 0 and "必需依赖" in result.stderr, "未拒绝缺失的必需依赖")
    print(f"PASS official_https_sha512_entries={count} negative_cases=3 downloads=false device=false")


if __name__ == "__main__":
    main()
