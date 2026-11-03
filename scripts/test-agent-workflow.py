#!/usr/bin/env python3
"""离线宿主回归：交接有效性与模拟验证保护，不调用真实ADB/Gradle。"""
from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
APP = "com.tanuki.modern.workspacecheck"
RECORD = "docs/handoffs/test-session.md"


class WorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="tanuki-agent-workflow-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "project with spaces"
        (self.root / "scripts").mkdir(parents=True)
        self.env = {**os.environ, "TANUKI_DEVICE": "", "VERIFIER_EVENTS": str(self.root / "events.jsonl")}

    def run_cmd(self, *args, **env):
        return subprocess.run(args, cwd=self.root, env={**self.env, **env}, capture_output=True, text=True)

    def put(self, name, text, executable=False):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        if executable:
            path.chmod(0o755)
        return path

    def handoff_fixture(self):
        shutil.copy2(ROOT / "scripts/check-handoff.py", self.root / "scripts/check-handoff.py")
        self.put("scripts/package-source.py", '''from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent
FILES = ("AGENTS.md", "README.md")
TREES = ("scripts", "docs", "app/src")
def public_file(path):
    return path.is_file() and "__pycache__" not in path.parts
''')
        self.put("AGENTS.md", "Agent rules\n")
        self.put("README.md", "Public project\n")
        self.put("app/src/main/example.kt", "// original\n")
        self.put("docs/AGENT-STATE.md", "[Latest](handoffs/test-session.md)\n")
        headings = ("用户目标与范围", "当前状态", "本次改动", "验证记录", "未完成与暂停项", "下一步", "约束与风险", "产物与校验")
        self.put(RECORD, "# Synthetic handoff\n" + "\n".join("## " + h + "\nSynthetic evidence.\n" for h in headings))
        result = self.checker("--record", RECORD)
        self.assertEqual(0, result.returncode, result.stderr)

    def checker(self, *args):
        return self.run_cmd(sys.executable, "scripts/check-handoff.py", *args)

    def test_workspace_isolation_applies_to_debug_and_release(self):
        script = (ROOT / "scripts/workspace-verification.init.gradle").read_text()
        for variant in ("debug", "release"):
            self.assertIn(f"getByName('{variant}').applicationIdSuffix = '.workspacecheck'", script)

    def test_release_signing_requires_keystore_outside_repository(self):
        self.runner_fixture()
        shutil.copy2(ROOT / "scripts/sign-release.sh", self.root / "scripts/sign-release.sh")
        self.put("docs/ARTIFACTS.md", "合成产物目录说明\n")
        inside = self.put("keys/release.jks", "synthetic-keystore")
        outside = Path(self.temp.name) / "release.jks"
        outside.write_text("synthetic-keystore")
        link = Path(self.temp.name) / "linked.jks"
        link.symlink_to(inside)
        for value in ("keys/release.jks", "./keys/release.jks", str(inside), str(link), "missing.jks"):
            result = self.run_cmd("bash", "scripts/sign-release.sh", TANUKI_KEYSTORE=value,
                                  TANUKI_STORE_PASSWORD="synthetic-password")
            self.assertEqual(64, result.returncode, result.stderr)
            self.assertFalse((self.root / "events.jsonl").exists())
        self.put("sdk/build-tools/34.0.0/apksigner", '''#!/usr/bin/env python3
import pathlib, sys
args = sys.argv[1:]
if args[0] == 'sign':
    pathlib.Path(args[args.index('--out') + 1]).write_bytes(b'synthetic-apk')
''', True)
        result = self.run_cmd("bash", "scripts/sign-release.sh", TANUKI_KEYSTORE=str(outside),
                              TANUKI_STORE_PASSWORD="synthetic-password", TANUKI_VERSION_NAME="0.1.1", TANUKI_VERSION_CODE="2")
        self.assertEqual(0, result.returncode, result.stderr)
        events = [json.loads(line) for line in (self.root / "events.jsonl").read_text().splitlines()]
        self.assertIn(["gradlew", "-PdevelopmentReleaseSigning=false", "-PtanukiVersionName=0.1.1", "-PtanukiVersionCode=2", ":app:assembleModernRelease"], events)
        folder = self.root / "artifacts/builds/production/0.1.1"
        self.assertEqual(b"synthetic-apk", (folder / "Tanuki-0.1.1-arm64-release.apk").read_bytes())
        self.assertTrue((folder / "release-signature.txt").is_file())
        self.assertTrue((folder / "release-SHA256SUMS").read_text().endswith("  Tanuki-0.1.1-arm64-release.apk\n"))
        self.assertFalse(list((self.root / "artifacts").glob("*.apk")))

    def test_clean_handoff_and_move_are_portable(self):
        self.handoff_fixture()
        self.assertEqual(0, self.checker().returncode)
        moved = self.root.with_name("moved project")
        shutil.copytree(self.root, moved)
        result = subprocess.run([sys.executable, "scripts/check-handoff.py"], cwd=moved, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_code_change_requires_written_handoff_not_just_restamping(self):
        self.handoff_fixture()
        self.put("app/src/main/example.kt", "// changed\n")
        self.assertNotEqual(0, self.checker().returncode)
        self.assertNotEqual(0, self.checker("--record", RECORD).returncode)
        with (self.root / RECORD).open("a") as output:
            output.write("\nUpdated: actual code change and verification.\n")
        self.assertEqual(0, self.checker("--record", RECORD).returncode)
        self.assertEqual(0, self.checker().returncode)

    def test_missing_section_and_wrong_current_link_are_rejected(self):
        self.handoff_fixture()
        self.put("docs/AGENT-STATE.md", "No current record link\n")
        self.assertNotEqual(0, self.checker().returncode)
        self.put("docs/AGENT-STATE.md", "[Latest](handoffs/test-session.md)\n")
        record = self.root / RECORD
        record.write_text(record.read_text().replace("## 验证记录", "## Removed"))
        self.assertNotEqual(0, self.checker("--record", RECORD).returncode)

    def test_generated_jni_does_not_invalidate_source_handoff(self):
        self.handoff_fixture()
        self.put("app/src/main/jniLibs/arm64-v8a/libsynthetic.so", "generated ELF placeholder")
        self.assertEqual(0, self.checker().returncode)

    def test_record_traversal_template_and_symlink_are_rejected(self):
        self.handoff_fixture()
        for bad in ("../record.md", "/tmp/record.md", "docs/handoffs/TEMPLATE.md"):
            with self.subTest(path=bad):
                self.assertNotEqual(0, self.checker("--record", bad).returncode)
        (self.root / "docs/handoffs/link.md").symlink_to("test-session.md")
        self.put("docs/AGENT-STATE.md", "[Latest](handoffs/link.md)\n")
        self.assertNotEqual(0, self.checker("--record", "docs/handoffs/link.md").returncode)

    def runner_fixture(self):
        shutil.copy2(ROOT / "scripts/verify-workspace.sh", self.root / "scripts/verify-workspace.sh")
        self.put("scripts/check-handoff.py", "print('Synthetic handoff guard')\n")
        self.put("scripts/env.sh", '''set -euo pipefail
TANUKI_PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TANUKI_SDK="$TANUKI_PROJECT/sdk"
TANUKI_ADB="$TANUKI_SDK/platform-tools/adb"
select_device() { "$TANUKI_ADB" -s "$TANUKI_DEVICE" get-state >/dev/null; }
''')
        common = '''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
with open(os.environ['VERIFIER_EVENTS'], 'a') as f:
    f.write(json.dumps([pathlib.Path(sys.argv[0]).name, *args]) + '\\n')
'''
        self.put("gradlew", common + "raise SystemExit(int(os.environ.get('FAKE_BUILD_EXIT', '0')))\n", True)
        self.put("sdk/cmdline-tools/latest/bin/apkanalyzer", common + f'''
app = {APP!r}
if args[:2] == ['manifest', 'application-id']:
    print(os.environ.get('FAKE_APP', app) if 'androidTest' not in args[-1] else app + '.test')
elif args[:2] == ['manifest', 'print']:
    target = os.environ.get('FAKE_TARGET', app)
    print('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><instrumentation android:targetPackage="' + target + '"/></manifest>')
else:
    raise SystemExit(2)
''', True)
        self.put("sdk/platform-tools/adb", common + '''
if args[-1] == 'get-state': print('device')
elif 'getprop' in args: print(os.environ.get('FAKE_ABI', 'arm64-v8a'))
elif 'install' in args: print('Success (mock only)')
elif 'instrument' in args:
    mode = os.environ.get('FAKE_RESULT', 'pass')
    print('INSTRUMENTATION_STATUS_CODE: 1')
    if mode == 'fail':
        print('INSTRUMENTATION_STATUS_CODE: -2\\nFAILURES!!!')
    elif mode == 'skip':
        print('INSTRUMENTATION_STATUS_CODE: -3\\nOK (1 test)')
    elif mode == 'incomplete':
        print('INSTRUMENTATION_STATUS_CODE: 0')
    else:
        print('INSTRUMENTATION_STATUS_CODE: 0\\nOK (1 test)')
    if mode == 'transport': raise SystemExit(7)
else: raise SystemExit(2)
''', True)

    def runner(self, *args, **env):
        return self.run_cmd("bash", "scripts/verify-workspace.sh", *args, **env)

    def events(self):
        path = self.root / "events.jsonl"
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def device_args(self):
        return ("--run-device-tests", "--confirm-test-data", "--device", "synthetic-device")

    def test_help_and_invalid_flags_touch_no_tools(self):
        self.runner_fixture()
        self.assertEqual(0, self.runner("--help").returncode)
        for args in (("--unknown",), ("--device",), ("--run-device-tests",), ("--confirm-test-data",), ("--device", "synthetic-device")):
            with self.subTest(args=args):
                self.assertNotEqual(0, self.runner(*args).returncode)
        self.assertEqual([], self.events())

    def test_default_only_builds_even_with_device_in_environment(self):
        self.runner_fixture()
        result = self.runner(TANUKI_DEVICE="configured-but-not-used")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["gradlew"], [event[0] for event in self.events()])
        self.assertIn("-PtestBuildType=debug", self.events()[0])
        self.assertIn("scripts/workspace-verification.init.gradle", self.events()[0])

    def test_package_target_and_abi_guards_block_installation(self):
        self.runner_fixture()
        for env in ({"FAKE_APP": "com.tanuki.modern"}, {"FAKE_TARGET": "wrong.target"}, {"FAKE_ABI": "x86_64"}):
            with self.subTest(env=env):
                result = self.runner(*self.device_args(), **env)
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertFalse(any("install" in event for event in self.events()))
                (self.root / "events.jsonl").unlink()

    def test_device_run_uses_explicit_serial_and_never_clears_data(self):
        self.runner_fixture()
        result = self.runner(*self.device_args())
        self.assertEqual(0, result.returncode, result.stderr)
        commands = [event for event in self.events() if event[0] == 'adb']
        self.assertTrue(commands)
        self.assertTrue(all(command[1:3] == ['-s', 'synthetic-device'] for command in commands))
        self.assertEqual(2, sum('install' in command for command in commands))
        self.assertFalse(any(item in {'uninstall', 'clear', 'force-stop'} for command in commands for item in command))
        self.assertIn('PASS: 1 Android tests', result.stdout)

    def test_failure_skip_incomplete_and_transport_are_not_passes(self):
        self.runner_fixture()
        for mode in ('fail', 'skip', 'incomplete', 'transport'):
            with self.subTest(mode=mode):
                result = self.runner(*self.device_args(), FAKE_RESULT=mode)
                self.assertNotEqual(0, result.returncode, result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
