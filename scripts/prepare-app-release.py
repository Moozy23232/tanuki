#!/usr/bin/env python3
"""用户确认版本后生成指定渠道的 APK、应用更新清单和对应源码；不创建 tag、不上传。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent.parent
VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?")
PACKAGES = {"workspacecheck": "com.tanuki.modern.workspacecheck", "production": "com.tanuki.modern"}


def validate_version(version, code):
    if len(version) > 80 or not VERSION.fullmatch(version) or not isinstance(code, int) or not 2 <= code <= 2_100_000_000:
        raise ValueError("先确认形如 0.1.1 或 0.1.1-rc.1 的版本，并提供高于所有已安装/已发布版本的 versionCode（至少 2）。")


def build_command(channel, version, code):
    validate_version(version, code)
    if channel == "workspacecheck":
        return ["./gradlew", "--no-daemon", "-I", "scripts/workspace-verification.init.gradle",
                "-PdevelopmentReleaseSigning=true", f"-PtanukiVersionName={version}", f"-PtanukiVersionCode={code}",
                ":app:assembleModernRelease"]
    if channel == "production":
        return ["bash", "scripts/sign-release.sh"]
    raise ValueError("必须明确 workspacecheck 或 production 渠道")


def digest(path):
    hasher = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()


def native_checksum(directory):
    # 发布目录扁平分发，清单不能沿用源码树中的 artifacts/ 相对路径。
    native = directory / "Tanuki-native-corresponding-sources.tar.gz"
    (directory / "native-source-SHA256SUMS").write_text(f"{digest(native)}  {native.name}\n")


def certificate_digest(text):
    values = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$", text, re.MULTILINE)
    if len(values) != 1:
        raise ValueError("APK 必须通过签名验证，并且仅有一个当前签名者")
    return values[0].lower()


def entry_for(apk, channel, version, code, *, actual_package, actual_version, actual_code, min_sdk, certificate):
    validate_version(version, code)
    if (actual_package != PACKAGES[channel] or actual_version != version or actual_code != code or
            not 26 <= min_sdk <= 34 or not re.fullmatch(r"[0-9a-f]{64}", certificate) or
            not apk.is_file() or not 1 <= apk.stat().st_size <= 256 * 1024 * 1024):
        raise ValueError("实际 APK 的包名、版本、Android 要求、证书或大小不符合发布请求")
    return dict(packageName=actual_package, versionName=version, versionCode=code, minSdk=min_sdk,
                assetName=f"Tanuki-{version}-{channel}-arm64-release.apk", sha256=digest(apk),
                bytes=apk.stat().st_size, certificateSha256=certificate)


def prior_entries(directory, version, code, document):
    entries = document.get("packages", [])
    if document.get("schemaVersion") != 1 or not isinstance(entries, list) or len(entries) > 8:
        raise ValueError("已有发布清单无效，不覆盖它")
    names = set()
    for entry in entries:
        name = entry.get("assetName", "")
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,159}", name) or entry.get("packageName") in names:
            raise ValueError("已有发布附件名称或包名重复无效")
        names.add(entry.get("packageName"))
        apk = directory / name
        if (entry.get("versionName") != version or entry.get("versionCode") != code or not apk.is_file() or
                apk.stat().st_size != entry.get("bytes") or digest(apk) != entry.get("sha256")):
            raise ValueError("已有发布与请求版本不一致，或附件丢失/已改变；请检查，不自动删除")
    return entries


def run(command, label, *, capture=False):
    print(f"[{label}] 开始", flush=True)
    started = time.monotonic()
    # 仅加载构建环境，不读取/打印整个环境变量，也不把密码放进命令行。
    result = subprocess.run(["bash", "-c", 'source scripts/env.sh; exec "$@"', "--", *map(str, command)],
                            cwd=ROOT, check=True, text=True, stdout=subprocess.PIPE if capture else None)
    print(f"[{label}] 完成 · {time.monotonic() - started:.1f} 秒", flush=True)
    return result.stdout or ""


def inspect_archive(apk, sdk):
    analyzer = sdk / "cmdline-tools/latest/bin/apkanalyzer"
    actual = {}
    for name, command in (("actual_package", "application-id"), ("actual_version", "version-name"),
                          ("actual_code", "version-code"), ("min_sdk", "min-sdk")):
        value = run([analyzer, "manifest", command, apk], f"核对 {command}", capture=True).strip()
        actual[name] = int(value) if name in {"actual_code", "min_sdk"} else value
    signing = run([sdk / "build-tools/34.0.0/apksigner", "verify", "--verbose", "--print-certs", apk], "验证 APK 签名", capture=True)
    actual["certificate"] = certificate_digest(signing)
    return actual


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True, help="必须先向用户询问并确认，不能自行定版本")
    parser.add_argument("--version-code", required=True, type=int, help="高于所有已发布/安装的版本代码")
    parser.add_argument("--channel", required=True, choices=tuple(PACKAGES))
    options = parser.parse_args()
    validate_version(options.version, options.version_code)
    if options.channel == "production" and ("TANUKI_KEYSTORE" not in os.environ or "TANUKI_STORE_PASSWORD" not in os.environ):
        raise ValueError("正式包需要仓库外已有密钥及环境密码；不能生成临时密钥冒充正式更新")
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT", Path.home() / "Library/Android/sdk"))
    directory = ROOT / "artifacts/releases" / options.version
    manifest = directory / "app-update.json"
    prior = prior_entries(directory, options.version, options.version_code, json.loads(manifest.read_text())) if manifest.exists() else []
    run([sys.executable, "scripts/check-handoff.py"], "确认最新交接")
    command = ["env", f"TANUKI_VERSION_NAME={options.version}", f"TANUKI_VERSION_CODE={options.version_code}",
               *build_command(options.channel, options.version, options.version_code)]
    run(command, "构建指定渠道")
    source_apk = (ROOT / "app/build/outputs/apk/modern/release/app-modern-release.apk" if options.channel == "workspacecheck"
                  else ROOT / "artifacts" / f"Tanuki-{options.version}-arm64-release.apk")
    entry = entry_for(source_apk, options.channel, options.version, options.version_code, **inspect_archive(source_apk, sdk))
    for old in prior:
        if old["packageName"] != entry["packageName"]:
            old_channel = next((key for key, package in PACKAGES.items() if package == old["packageName"]), None)
            if old_channel is None or entry_for(directory / old["assetName"], old_channel, options.version, options.version_code,
                                                **inspect_archive(directory / old["assetName"], sdk)) != old:
                raise ValueError("已有其他渠道 APK 的实际身份与清单不符")
    directory.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source_apk, directory / entry["assetName"])
    entries = [item for item in prior if item["packageName"] != entry["packageName"]] + [entry]
    manifest.write_text(json.dumps(dict(schemaVersion=1, packages=entries), ensure_ascii=False, indent=2) + "\n")
    run(["bash", "scripts/package-native-sources.sh"], "打包原生对应源码")
    run([sys.executable, "scripts/package-source.py", "--version", options.version], "打包本次完整源码")
    source_zip = f"Tanuki-{options.version}-source.zip"
    for name in (source_zip, "Tanuki-native-corresponding-sources.tar.gz"):
        shutil.copy2(ROOT / "artifacts" / name, directory / name)
    native_checksum(directory)
    selected = [directory / item["assetName"] for item in entries] + [manifest, directory / source_zip,
                directory / "Tanuki-native-corresponding-sources.tar.gz", directory / "native-source-SHA256SUMS"]
    (directory / "RELEASE-SHA256SUMS").write_text("".join(f"{digest(path)}  {path.name}\n" for path in selected))
    print(f"完成：{directory.relative_to(ROOT)}；未创建 tag、上传 Release 或操作设备。")
    print("发布时把该目录附件上传到同一 Release；workspacecheck 必须标为 Pre-release。")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"打包停止：{error}", file=sys.stderr)
        raise SystemExit(64)
