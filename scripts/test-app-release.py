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
        (source.ROOT / "docs").mkdir()
        (source.ROOT / "docs/ARTIFACTS.md").write_text("合成目录说明")
        with contextlib.redirect_stdout(io.StringIO()):
            source.main("0.1.1")
        output = source.ROOT / "artifacts/sources/Tanuki-0.1.1-source.zip"
        with zipfile.ZipFile(output) as archive:
            self.assertEqual(["Tanuki-0.1.1/fixture.txt"], archive.namelist())
        expected = f"{hashlib.sha256(output.read_bytes()).hexdigest()}  sources/{output.name}\n"
        self.assertEqual(expected, (source.ROOT / "artifacts/SOURCE-SHA256SUMS").read_text())
        self.assertEqual("合成目录说明", (source.ROOT / "artifacts/README.md").read_text())
        self.assertFalse((source.ROOT / "artifacts" / output.name).exists())

    def test_release_collects_only_categorized_source_outputs(self):
        root = self.apk.parent
        sources = root / "artifacts/sources"
        (sources / "native").mkdir(parents=True)
        (sources / "Tanuki-0.1.1-source.zip").write_bytes(b"synthetic-source")
        (sources / "native/Tanuki-native-corresponding-sources.tar.gz").write_bytes(b"synthetic-native")
        bundle = root / "artifacts/releases/0.1.1"
        bundle.mkdir(parents=True)
        release.copy_source_attachments(root, bundle, "0.1.1")
        self.assertEqual(b"synthetic-source", (bundle / "Tanuki-0.1.1-source.zip").read_bytes())
        self.assertEqual(b"synthetic-native", (bundle / "Tanuki-native-corresponding-sources.tar.gz").read_bytes())
        self.assertIn("  Tanuki-native-corresponding-sources.tar.gz\n", (bundle / "native-source-SHA256SUMS").read_text())
        self.assertEqual(3, len(list(bundle.iterdir())))

    def test_native_source_allowlist_follows_new_directory_only(self):
        spec = importlib.util.spec_from_file_location("source_package", ROOT / "scripts/package-source.py")
        source = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(source)
        expected = {"artifacts/sources/native/Tanuki-native-corresponding-sources.tar.gz", "artifacts/sources/native/native-source-SHA256SUMS"}
        self.assertEqual(expected, set(source.EXTRAS))
        for name in expected:
            self.assertTrue(source.public_name(Path(name)))
        self.assertFalse(source.public_name(Path("artifacts/archive/old.apk")))
        self.assertFalse(source.public_name(Path("artifacts/Tanuki-native-corresponding-sources.tar.gz")))

    def test_production_apk_is_staged_separately_from_release_bundles(self):
        path = release.build_apk_path(self.apk.parent, "production", "0.1.1")
        self.assertEqual(self.apk.parent / "artifacts/builds/production/0.1.1/Tanuki-0.1.1-arm64-release.apk", path)
        self.assertIn('artifacts/builds/production/$TANUKI_VERSION_NAME', (ROOT / "scripts/sign-release.sh").read_text())
        self.assertEqual(self.apk.parent / "app/build/outputs/apk/modern/release/app-modern-release.apk",
                         release.build_apk_path(self.apk.parent, "workspacecheck", "0.1.1"))

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
