#!/usr/bin/env python3
"""检查公开候选文件，不读取被忽略的秘密文件，也不输出匹配值。"""
from __future__ import annotations

import argparse
import importlib.util
import io
import json
from pathlib import Path
import re
import subprocess
import sys
import tarfile
from urllib.parse import unquote, urlsplit
import zipfile

ROOT = Path(__file__).resolve().parent.parent
RULES = (
    ("brand-consistency", b"(?i)har" + b"ness" + rb"[\s_./\\-]*" + b"po" + b"cket"),
    ("private-key", rb"-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----"),
    ("cloud-access-key", rb"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
    ("api-key", rb"\bsk-(?:proj-|ant-api\d+-)?[A-Za-z0-9_-]{20,}"),
    ("google-api-key", rb"\bAIza[A-Za-z0-9_-]{35}\b"),
    ("github-token", rb"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})"),
)
EMAIL = re.compile(rb"\b[A-Za-z0-9_.+-]+@([A-Za-z0-9.-]+\.[A-Za-z]{2,})\b")
LINK = re.compile(r"!?\[[^\]\n]*\]\(([^\s)]+)(?:\s+[^)]*)?\)")
MAX_ARCHIVE_BYTES = 128 * 1024 * 1024
MAX_MEMBERS = 10000
ZIP_SUFFIXES = (".zip", ".apk", ".aab", ".apks")


def kotlin_code_positions(data: bytes):
    """区分 Kotlin 代码与文本，保留字符串插值中的嵌套字符串边界。"""
    code = bytearray(len(data))
    stack = [["code", 0]]
    i = 0
    while i < len(data):
        mode = stack[-1][0]
        if mode in {"code", "template"}:
            if data.startswith(b"//", i):
                end = data.find(b"\n", i)
                i = len(data) if end < 0 else end
                continue
            if data.startswith(b"/*", i):
                stack.append(["block", 1]); i += 2; continue
            if data.startswith(b'"""', i):
                stack.append(["raw", 0]); i += 3; continue
            if data[i] in (34, 39, 96):
                stack.append([{34: "string", 39: "char", 96: "backtick"}[data[i]], 0])
                i += 1; continue
            if mode == "template":
                if data[i] == 123:
                    stack[-1][1] += 1
                elif data[i] == 125:
                    stack[-1][1] -= 1
                    if stack[-1][1] == 0:
                        stack.pop(); i += 1; continue
            code[i] = 1
            i += 1
        elif mode == "block":
            if data.startswith(b"/*", i):
                stack[-1][1] += 1; i += 2
            elif data.startswith(b"*/", i):
                stack[-1][1] -= 1; i += 2
                if stack[-1][1] == 0:
                    stack.pop()
            else:
                i += 1
        else:
            closing = {"string": b'"', "raw": b'"""', "char": b"'", "backtick": b"`"}[mode]
            if mode in {"string", "char"} and data[i] == 92:
                i += 2
            elif data.startswith(closing, i):
                stack.pop(); i += len(closing)
            elif mode in {"string", "raw"} and data.startswith(b"${", i):
                stack.append(["template", 1]); i += 2
            else:
                i += 1
    return code


def content_findings(data: bytes, markers: tuple[bytes, ...], attribution: bool = False, kotlin: bool = False):
    """只返回行号和规则名称，不返回凭据或身份信息片段。"""
    found = []
    for marker in markers:
        if marker and (position := data.lower().find(marker.lower())) >= 0:
            found.append((data.count(b"\n", 0, position) + 1, "local-identity"))
    for name, pattern in RULES:
        for match in re.finditer(pattern, data):
            found.append((data.count(b"\n", 0, match.start()) + 1, name))
    if not attribution and b"\0" not in data:
        # 只排除确定位于代码中的接收者语法，嵌套插值内的邮箱仍需检查。
        code = kotlin_code_positions(data) if kotlin else None
        for match in EMAIL.finditer(data):
            if (code is not None and code[match.start()]
                    and match.group(0).startswith((b"this@", b"super@"))):
                continue
            domain = match.group(1).lower()
            if (domain in {b"example.com", b"example.org", b"example.net", b"invalid.example"}
                    or domain.endswith((b".example", b".invalid", b".localhost", b".users.noreply.github.com"))
                    or domain == b"users.noreply.github.com"):
                continue
            found.append((data.count(b"\n", 0, match.start()) + 1, "review-email"))
    return sorted(set(found))


def safe_name(name: str, markers: tuple[bytes, ...]) -> str:
    data = name.encode()
    for marker in markers:
        if marker:
            data = re.sub(re.escape(marker), b"<private>", data, flags=re.IGNORECASE)
    for _, pattern in RULES:
        data = re.sub(pattern, b"<credential>", data)
    data = EMAIL.sub(b"<email>", data)
    return "".join(c if c.isprintable() else "?" for c in data.decode(errors="replace"))


class ArchiveBudget:
    def __init__(self, max_bytes=None, max_members=None):
        self.bytes = self.members = 0
        self.max_bytes = MAX_ARCHIVE_BYTES if max_bytes is None else max_bytes
        self.max_members = MAX_MEMBERS if max_members is None else max_members

    def consume(self, size):
        self.bytes += size
        self.members += 1
        return size >= 0 and self.bytes <= self.max_bytes and self.members <= self.max_members


def limited_read(stream):
    data = stream.read(MAX_ARCHIVE_BYTES + 1)
    if len(data) > MAX_ARCHIVE_BYTES:
        raise ValueError("Public file exceeds inspection limit")
    return data


def scan_blob(name: str, data: bytes, markers: tuple[bytes, ...], depth: int = 0, budget=None):
    member = name.split("!")[-1].removeprefix("./")
    attribution = (member.startswith("licenses/") or "/licenses/" in member
                   or member.rsplit("/", 1)[-1] == "THIRD_PARTY_NOTICES"
                   or ("native-corresponding-sources.tar.gz!" in name and member.startswith(
                       (".build/sources/termux-proot/", ".build/sources/talloc-2.4.3/"))))
    results = [(name, line, rule) for line, rule in content_findings(data, markers, attribution, member.endswith(".kt"))]
    results += [(name, 0, rule) for _, rule in content_findings(name.encode(), markers)]
    if not name.endswith(ZIP_SUFFIXES + (".tar.gz", ".tgz", ".tar")):
        return results
    budget = ArchiveBudget() if budget is None else budget
    if depth >= 3 or len(data) > MAX_ARCHIVE_BYTES:
        return results + [(name, 0, "archive-limit")]
    try:
        if name.endswith(ZIP_SUFFIXES):
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                for entry in archive.infolist():
                    if not budget.consume(entry.file_size):
                        return results + [(name, 0, "archive-limit")]
                    if not entry.is_dir():
                        with archive.open(entry) as stream:
                            content = limited_read(stream)
                        if len(content) != entry.file_size:
                            return results + [(name, 0, "archive-size-mismatch")]
                        results += scan_blob(name + "!" + entry.filename, content, markers, depth + 1, budget)
        else:
            # 流式读取 TAR 条目，避免先创建不受限的成员列表。
            with tarfile.open(fileobj=io.BytesIO(data), mode="r|*") as archive:
                for entry in archive:
                    if not budget.consume(entry.size):
                        return results + [(name, 0, "archive-limit")]
                    metadata = json.dumps({"name": entry.name, "link": entry.linkname, "user": entry.uname,
                                           "group": entry.gname, "pax": entry.pax_headers}, ensure_ascii=False).encode()
                    results += [(name, 0, rule) for _, rule in content_findings(metadata, markers)]
                    if entry.isfile():
                        with archive.extractfile(entry) as stream:
                            content = limited_read(stream)
                        results += scan_blob(name + "!" + entry.name, content, markers, depth + 1, budget)
    except (OSError, ValueError, tarfile.TarError, zipfile.BadZipFile) as error:
        raise ValueError(f"Cannot inspect archive ({type(error).__name__})") from None
    return results


def link_findings(root: Path, paths: list[Path]):
    results = []
    for path in paths:
        relative = path.relative_to(root).as_posix()
        if path.suffix != ".md" or not (relative.startswith("docs/") or relative in {"README.md", "AGENTS.md"}):
            continue
        text = path.read_text()
        for match in LINK.finditer(text):
            target = urlsplit(match.group(1))
            if target.scheme or not target.path:
                continue
            resolved = (path.parent / unquote(target.path)).resolve()
            if not resolved.is_relative_to(root.resolve()) or not resolved.exists():
                results.append((relative, text.count("\n", 0, match.start()) + 1, "broken-local-link"))
    return results


def distribution():
    spec = importlib.util.spec_from_file_location("public_distribution", ROOT / "scripts/package-source.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def git_run(*arguments, **options):
    return subprocess.run(["git", *arguments], cwd=ROOT, check=True, capture_output=True, **options).stdout


def git_paths():
    names = sorted(set(filter(None, git_run("ls-files", "--cached", "--others", "--exclude-standard", "-z").split(b"\0"))))
    ignored = subprocess.run(["git", "check-ignore", "--no-index", "-z", "--stdin"],
                             cwd=ROOT, input=b"\0".join(names) + b"\0", capture_output=True)
    if ignored.returncode not in (0, 1):
        raise ValueError("Cannot check Git ignore rules")
    staged = []
    for record in filter(None, git_run("ls-files", "--stage", "-z").split(b"\0")):
        metadata, name = record.split(b"\t", 1)
        mode, oid, stage = metadata.decode().split()
        staged.append((name.decode(), mode, oid, stage))
    return [ROOT / name.decode() for name in names], set(filter(None, ignored.stdout.split(b"\0"))), staged


def allowed_candidate(package, name):
    relative = Path(name)
    return (name in package.FILES or any(relative.is_relative_to(tree) for tree in package.TREES)
            or name in package.EXTRAS) and package.public_name(relative)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--git", action="store_true", help="Check index blobs and non-ignored worktree candidates, not Git history")
    parser.add_argument("--archive", type=Path, help="Also inspect a generated public archive and nested archives")
    args = parser.parse_args()
    markers = [str(Path.home()).encode(), str(ROOT).encode()]
    username = Path.home().name
    if len(username) >= 4 and username.lower() not in {"root", "user", "runner", "developer"}:
        markers.append(username.encode())
    markers = tuple(markers)
    try:
        package = distribution()
        if args.git:
            paths, ignored, staged = git_paths()
        else:
            paths = [ROOT / name for name in package.FILES]
            paths += [ROOT / name for name in package.EXTRAS if (ROOT / name).exists()]
            paths += [p for tree in package.TREES for p in (ROOT / tree).rglob("*") if package.public_file(p)]
            ignored, staged = set(), []
        results, public = [], []
        for path in sorted(set(paths)):
            relative = path.relative_to(ROOT).as_posix()
            if relative.encode() in ignored or not allowed_candidate(package, relative):
                results.append((relative, 0, "private-or-generated-candidate"))
            elif path.is_symlink():
                results.append((relative, 0, "source-symlink"))
            elif path.exists():
                public.append(path)
                with path.open("rb") as stream:
                    results += scan_blob(relative, limited_read(stream), markers)
        for relative, mode, oid, stage in staged:
            if relative.encode() in ignored or not allowed_candidate(package, relative):
                results.append(("[index] " + relative, 0, "private-or-generated-candidate"))
            elif mode not in {"100644", "100755"} or stage != "0":
                results.append(("[index] " + relative, 0, "index-mode-or-conflict"))
            elif int(git_run("cat-file", "-s", oid)) > MAX_ARCHIVE_BYTES:
                results.append(("[index] " + relative, 0, "file-limit"))
            else:
                findings = scan_blob(relative, git_run("cat-file", "blob", oid), markers)
                results += [("[index] " + name, line, rule) for name, line, rule in findings]
        results += link_findings(ROOT, public)
        if args.archive:
            with args.archive.open("rb") as stream:
                results += scan_blob(args.archive.name, limited_read(stream), markers)
        for name, line, rule in sorted(set(results)):
            print(f"{safe_name(name, markers)}:{line}: {rule}")
        print(f"Publication check: {len(public)} public files, {len(staged)} index entries, {len(set(results))} findings; matched values are never printed")
        return int(bool(results))
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(f"Publication check failed ({type(error).__name__}); no matched content printed", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
