#!/usr/bin/env python3
"""检查可迁移交接是否有效，不依赖 Git、本机缓存或设备访问。"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path, PurePosixPath
import sys

ROOT = Path(__file__).resolve().parent.parent
STAMP = ROOT / "docs/handoffs/CURRENT.json"
REQUIRED = (
    "## 用户目标与范围", "## 当前状态", "## 本次改动", "## 验证记录",
    "## 未完成与暂停项", "## 下一步", "## 约束与风险", "## 产物与校验",
)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def record_path(name: str) -> Path:
    relative = PurePosixPath(name)
    if (relative.is_absolute() or ".." in relative.parts or len(relative.parts) != 3
            or relative.parts[:2] != ("docs", "handoffs") or relative.suffix != ".md"
            or relative.name == "TEMPLATE.md"):
        raise ValueError("Record must be docs/handoffs/<unique-session>.md, not TEMPLATE.md")
    path = ROOT.joinpath(*relative.parts)
    if any(ROOT.joinpath(*relative.parts[:i]).is_symlink() for i in range(1, len(relative.parts) + 1)):
        raise ValueError("Handoff symlinks are not supported")
    text = path.read_text()
    headings = set(line.strip() for line in text.splitlines())
    missing = [heading for heading in REQUIRED if heading not in headings]
    if missing:
        raise ValueError("Missing handoff sections: " + ", ".join(missing))
    state = (ROOT / "docs/AGENT-STATE.md").read_text()
    if f"handoffs/{relative.name}" not in state:
        raise ValueError("docs/AGENT-STATE.md must link to this handoff record")
    return path


def fingerprint() -> tuple[str, int]:
    # 复用源码包白名单，确保新解包或迁移后的目录具有相同指纹。
    spec = importlib.util.spec_from_file_location("public_distribution", ROOT / "scripts/package-source.py")
    if spec is None or spec.loader is None:
        raise ValueError("Cannot load the source distribution allowlist")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    paths: set[Path] = set()
    for name in module.FILES:
        path = ROOT / name
        if not path.is_file():
            raise ValueError(f"Required public file missing: {name}")
        if module.public_file(path):
            paths.add(path)
    for tree in module.TREES:
        directory = ROOT / tree
        if not directory.is_dir():
            raise ValueError(f"Required public directory missing: {tree}")
        paths.update(p for p in directory.rglob("*") if module.public_file(p))
    selected = []
    for path in paths:
        relative = path.relative_to(ROOT).as_posix()
        # 印章不计算自身散列；原生 ELF 带有宿主诊断差异，需要重新构建。
        # 对应源码、构建脚本和补丁仍参与指纹计算。
        if path == STAMP or relative.startswith("app/src/main/jniLibs/"):
            continue
        selected.append((relative, path))
    hasher = hashlib.sha256()
    for relative, path in sorted(selected):
        hasher.update(relative.encode("utf-8") + b"\0" + digest(path).encode("ascii") + b"\n")
    return hasher.hexdigest(), len(selected)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--record", metavar="PATH", help="After writing a handoff/state, refresh the portable stamp")
    args = parser.parse_args()
    try:
        previous = json.loads(STAMP.read_text()) if STAMP.exists() else None
        if previous is not None and previous.get("schema") != 1:
            raise ValueError("Unsupported handoff stamp schema")
        name = args.record or (previous or {}).get("record")
        if not isinstance(name, str):
            raise ValueError("No handoff stamp: write a record/state, then use --record docs/handoffs/<session>.md")
        path = record_path(name)
        source_hash, count = fingerprint()
        record_hash = digest(path)
        if args.record:
            if (previous and previous.get("sourceFingerprint") != source_hash
                    and previous.get("record") == name and previous.get("recordSha256") == record_hash):
                raise ValueError("Project changed but the handoff text did not. Update it or create a new session record first")
            stamp = {"schema": 1, "record": name, "recordSha256": record_hash,
                     "sourceFingerprint": source_hash, "fileCount": count}
            # 源码改变必须更新交接；新 Agent 即使只读或未改代码，
            # 也须创建本次独立记录，具体要求见 AGENTS.md。
            temporary = STAMP.with_suffix(".json.tmp")
            temporary.write_text(json.dumps(stamp, ensure_ascii=False, indent=2) + "\n")
            temporary.replace(STAMP)
            print(f"Handoff refreshed: {name} ({count} public files)")
        else:
            if (previous.get("sourceFingerprint") != source_hash
                    or previous.get("recordSha256") != record_hash or previous.get("fileCount") != count):
                raise ValueError("Stale handoff. Update the record/state and run --record before verification/delivery")
            print(f"Handoff current: {name} ({count} public files)")
    except (OSError, ValueError, TypeError, AttributeError, KeyError) as error:
        print(f"Handoff check failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
