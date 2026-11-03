#!/usr/bin/env python3
"""按明确的公开源码清单打包可重建工程。"""
from pathlib import Path
import argparse
import hashlib
import re
import shutil
import zipfile

ROOT = Path(__file__).resolve().parent.parent
NAME = "Tanuki-0.1.1"
OUTPUT = ROOT / "artifacts/sources" / f"{NAME}-source.zip"
FILES = (
    ".gitignore", "AGENTS.md", "LICENSE", "README.md", "THIRD_PARTY_NOTICES",
    "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
    "gradlew", "gradlew.bat", "app/build.gradle.kts",
)
# 原生对应源码附件不作为 Git 检出或可迁移源码指纹的前置条件；
# 两个附件都已生成时才一并打包，以满足二进制分发的许可要求。
EXTRAS = ("artifacts/sources/native/Tanuki-native-corresponding-sources.tar.gz", "artifacts/sources/native/native-source-SHA256SUMS")
TREES = ("app/src", "gradle", "runtime", "scripts", "docs", "licenses")
OMIT_PARTS = {"__pycache__", ".DS_Store", ".git", ".gradle", ".build", "build", "private", ".private",
              ".idea", ".vscode", ".ssh", ".aws", ".kotlin", ".cxx", ".externalNativeBuild", ".cache",
              "node_modules", ".npm", ".pnpm-store", ".venv", "venv", ".pytest_cache", ".mypy_cache",
              ".ruff_cache", ".pi", ".dsh", "captures", "out", "dist", "artifacts"}
OMIT_NAMES = {"local.properties", ".npmrc", ".pypirc", ".netrc", ".git-credentials", ".envrc",
              "keystore.properties", "signing.properties", "key.properties", "credentials.json"}
OMIT_SUFFIXES = {".pyc", ".pyo", ".pyd", ".keystore", ".jks", ".p12", ".pfx", ".key", ".pem", ".iml",
                 ".log", ".har", ".hprof", ".trace", ".dmp", ".heapsnapshot", ".pcap", ".pcapng",
                 ".apk", ".aab", ".apks", ".dex", ".class", ".o", ".so", ".a", ".bak", ".orig", ".rej",
                 ".db", ".db-shm", ".db-wal", ".sqlite", ".sqlite3", ".sqlite-shm", ".sqlite-wal"}


def public_name(relative: Path) -> bool:
    if relative.parts[0] == "artifacts":
        return relative.as_posix() in EXTRAS
    # 与 Git 的纯源码边界保持一致，原生 ELF 从固定来源重建。
    if relative.parts[:4] == ("app", "src", "main", "jniLibs"):
        return False
    if relative.parts[:2] == ("docs", "evidence"):
        return False
    if relative.name.endswith((".zip", ".tar", ".tar.gz", ".tgz", ".7z")):
        return False
    if any(part in OMIT_PARTS for part in relative.parts):
        return False
    if (relative.name in OMIT_NAMES or relative.name.startswith((".env", "id_rsa", "id_ed25519", "service-account"))
            or relative.name.endswith("~") or relative.suffix.lower() in OMIT_SUFFIXES):
        return False
    return True


def public_file(path: Path) -> bool:
    relative = path.relative_to(ROOT)
    if not public_name(relative):
        return False
    if path.is_symlink():
        raise ValueError(f"Source symlinks require explicit handling: {relative}")
    return path.is_file()


def main(version=None) -> None:
    # 导入本模块做公共指纹时仍保留默认常量；只有显式发布调用才改变归档名称。
    if version is not None and (len(version) > 80 or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?", version)):
        raise ValueError("Invalid confirmed source version")
    archive_name = f"Tanuki-{version}" if version is not None else NAME
    output = ROOT / "artifacts/sources" / f"{archive_name}-source.zip"
    selected = set()
    for name in FILES:
        path = ROOT / name
        if not path.is_file():
            raise FileNotFoundError(f"Required distribution file missing: {name}")
        if public_file(path):
            selected.add(path)
    for tree in TREES:
        directory = ROOT / tree
        if not directory.is_dir():
            raise FileNotFoundError(f"Required source directory missing: {tree}")
        for path in directory.rglob("*"):
            if public_file(path):
                selected.add(path)
    extras = [ROOT / name for name in EXTRAS]
    if any(path.exists() for path in extras):
        if not all(path.is_file() and public_file(path) for path in extras):
            raise FileNotFoundError("Incomplete native source attachments; run scripts/package-native-sources.sh")
        selected.update(extras)
    output.parent.mkdir(parents=True, exist_ok=True)
    guide = ROOT / "docs/ARTIFACTS.md"
    if guide.is_file():
        shutil.copy2(guide, ROOT / "artifacts/README.md")
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(selected):
            relative = path.relative_to(ROOT)
            info = zipfile.ZipInfo(f"{archive_name}/{relative.as_posix()}", date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            executable = path.name == "gradlew" or path.suffix in {".sh", ".py", ".mjs"}
            info.external_attr = (0o100755 if executable else 0o100644) << 16
            archive.writestr(info, path.read_bytes(), compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    hasher = hashlib.sha256()
    with output.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(chunk)
    digest = hasher.hexdigest()
    checksum = ROOT / "artifacts/SOURCE-SHA256SUMS"
    checksum.write_text(f"{digest}  sources/{output.name}\n")
    print(f"source_files={len(selected)} bytes={output.stat().st_size}")
    print("native_sources=" + ("included" if all(path in selected for path in extras)
                               else "absent; prepare corresponding sources before distributing native binaries"))
    print(f"archive={output.relative_to(ROOT)}")
    print(f"sha256={digest}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", help="用户已确认的发布版本；日常检查不传")
    main(parser.parse_args().version)
