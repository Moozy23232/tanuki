#!/usr/bin/env python3
"""发布打包的离线回归；只用合成 APK 字节与元数据，不签名、不运行 Gradle。"""
import hashlib
import contextlib
import io
import zipfile
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("app_release", ROOT / "scripts/prepare-app-release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class AppReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="tanuki-release-test-")
        self.addCleanup(self.temp.cleanup)
        self.apk = Path(self.temp.name) / "fixture.apk"
        self.apk.write_bytes(b"synthetic-apk-no-private-data")
        self.metadata = dict(actual_package="com.tanuki.modern.workspacecheck", actual_version="0.1.1", actual_code=2,
                             min_sdk=26, certificate="a" * 64)

    def entry(self, **changes):
        return release.entry_for(self.apk, "workspacecheck", "0.1.1", 2, **(self.metadata | changes))

    def test_version_is_explicit_safe_and_code_increases_past_development(self):
        for name in ("0.1.1", "1.2.3-rc.1"):
            release.validate_version(name, 2)
        for name in ("", "latest", "../1.0.0", "v1.2.3", "1.2.3\n", "1.2.3;echo bad"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                release.validate_version(name, 2)
        for code in (-1, 0, 1, 2_100_000_001):
            with self.assertRaises(ValueError):
                release.validate_version("0.1.1", code)

    def test_workspace_build_is_isolated_and_receives_confirmed_version(self):
        command = release.build_command("workspacecheck", "0.1.1", 2)
        self.assertIn("scripts/workspace-verification.init.gradle", command)
        self.assertIn("-PtanukiVersionName=0.1.1", command)
        self.assertIn("-PtanukiVersionCode=2", command)
        self.assertIn("-PdevelopmentReleaseSigning=true", command)
        self.assertIn(":app:assembleModernRelease", command)

    def test_integrity_uses_actual_apk_bytes_and_identity(self):
        entry = self.entry()
        self.assertEqual(hashlib.sha256(self.apk.read_bytes()).hexdigest(), entry["sha256"])
        self.assertEqual(self.apk.stat().st_size, entry["bytes"])
        self.assertEqual("com.tanuki.modern.workspacecheck", entry["packageName"])
        self.assertEqual(2, entry["versionCode"])

    def test_wrong_package_version_or_sdk_cannot_be_published(self):
        for changes in (dict(actual_package="com.tanuki.modern"), dict(actual_version="0.1.0-dev"), dict(actual_code=1),
                        dict(min_sdk=35), dict(certificate="")):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.entry(**changes)

    def test_certificate_is_parsed_only_from_one_actual_signer(self):
        text = "Signer #1 certificate SHA-256 digest: " + "a" * 64
        self.assertEqual("a" * 64, release.certificate_digest(text))
        for unsafe in ("", text + "\n" + text.replace("#1", "#2"), "Signer #1 public key SHA-256 digest: " + "a" * 64):
            with self.assertRaises(ValueError):
                release.certificate_digest(unsafe)

    def test_cli_missing_version_cannot_trigger_a_build(self):
        result = subprocess.run([sys.executable, str(ROOT / "scripts/prepare-app-release.py"), "--channel", "workspacecheck"], capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("--version", result.stderr)

    def test_source_archive_keeps_confirmed_root_directory(self):
        spec = importlib.util.spec_from_file_location("source_package", ROOT / "scripts/package-source.py")
        source = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(source)
        source.ROOT = self.apk.parent
        source.FILES, source.TREES, source.EXTRAS = ("fixture.txt",), (), ()
        (source.ROOT / "fixture.txt").write_text("synthetic-public-source")
        with contextlib.redirect_stdout(io.StringIO()):
            source.main("0.1.1")
        with zipfile.ZipFile(source.ROOT / "artifacts/Tanuki-0.1.1-source.zip") as archive:
            self.assertEqual(["Tanuki-0.1.1/fixture.txt"], archive.namelist())

    def test_native_checksum_is_relative_to_the_published_bundle(self):
        native = self.apk.parent / "Tanuki-native-corresponding-sources.tar.gz"
        native.write_bytes(b"synthetic-native-sources")
        release.native_checksum(self.apk.parent)
        expected = f"{hashlib.sha256(native.read_bytes()).hexdigest()}  {native.name}\n"
        self.assertEqual(expected, (native.parent / "native-source-SHA256SUMS").read_text())
        self.assertNotIn("artifacts/", expected)

    def test_existing_bundle_must_be_same_version_and_match_local_apks(self):
        entry = self.entry()
        # 合成文件名必须与条目相符，不能靠清单描述不存在的附件。
        good = self.apk.with_name(entry["assetName"])
        good.write_bytes(self.apk.read_bytes())
        self.assertEqual([entry], release.prior_entries(self.apk.parent, "0.1.1", 2, {"schemaVersion": 1, "packages": [entry]}))
        good.write_bytes(b"corrupted")
        with self.assertRaises(ValueError):
            release.prior_entries(self.apk.parent, "0.1.1", 2, {"schemaVersion": 1, "packages": [entry]})


if __name__ == "__main__":
    unittest.main()
