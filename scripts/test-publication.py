#!/usr/bin/env python3
"""公开源码过滤的离线回归，所有看似敏感的数据均为合成值。"""
import importlib.util
import io
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), ROOT / "scripts" / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


SCAN = load("check-publication")
PACKAGE = load("package-source")


class PublicationTests(unittest.TestCase):
    def test_current_tanuki_identifiers_are_accepted(self):
        data = b"com.tanuki TanukiApp Theme.Tanuki TANUKI_IDENTITY /home/tanuki"
        self.assertEqual([], SCAN.content_findings(data, ()))

    def test_brand_consistency_covers_case_separators_paths_and_archive_members(self):
        first, second = b"har" + b"ness", b"po" + b"cket"
        for data in (first + second, (first + second).upper(), first + b"_" + second,
                     first + b"-" + second, first + b"/" + second):
            self.assertIn((1, "brand-consistency"), SCAN.content_findings(data, ()))
        name = (b"com/" + first + second + b"/Demo.kt").decode()
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as output:
            output.writestr(name, b"package com.tanuki")
        findings = SCAN.scan_blob("source.zip", archive.getvalue(), ())
        self.assertIn(("source.zip!" + name, 0, "brand-consistency"), findings)

    def test_credential_findings_contain_only_rule_and_line(self):
        fake = b"sk-" + b"x" * 32
        findings = SCAN.content_findings(b"example\n" + fake, ())
        self.assertEqual([(2, "api-key")], findings)
        self.assertNotIn(fake.decode(), repr(findings))
        pem = b"-----" + b"BEGIN PRIVATE KEY" + b"-----"
        self.assertIn((1, "private-key"), SCAN.content_findings(pem, ()))

    def test_local_identity_is_found_in_binary_without_returning_it(self):
        marker = b"synthetic-private-user"
        findings = SCAN.content_findings(b"binary\0" + marker.upper(), (marker,))
        self.assertEqual([(1, "local-identity")], findings)
        self.assertNotIn(marker.decode(), repr(findings))

    def test_example_mail_and_required_attribution_are_not_rewritten(self):
        self.assertEqual([], SCAN.content_findings(b"tester@invalid.example", ()))
        self.assertEqual([], SCAN.content_findings(b"tester@users.noreply.github.com", ()))
        mail = b"contributor@" + b"upstream.org"
        self.assertEqual([(1, "review-email")], SCAN.content_findings(mail, ()))
        self.assertEqual([], SCAN.content_findings(mail, (), attribution=True))

    def test_archived_first_party_mail_is_checked_but_upstream_attribution_is_preserved(self):
        data = b"contributor@" + b"upstream.org"
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as output:
            output.writestr("Tanuki/README.md", data)
            output.writestr("Tanuki/licenses/NOTICE", data)
        findings = SCAN.scan_blob("source.zip", archive.getvalue(), ())
        self.assertIn(("source.zip!Tanuki/README.md", 1, "review-email"), findings)
        self.assertFalse(any("licenses/NOTICE" in name for name, _, _ in findings))

    def test_kotlin_labeled_receivers_are_not_email_but_strings_still_are(self):
        receiver = b"class Demo { val x = this@" + b"Demo.member }"
        self.assertEqual([], SCAN.scan_blob("Demo.kt", receiver, ()))
        quoted = b'class Demo { val x = "Contact this@' + b'Demo.org" }'
        self.assertIn(("Demo.kt", 1, "review-email"), SCAN.scan_blob("Demo.kt", quoted, ()))
        extension = b"fun String.demo() { println(this@" + b"demo.length) }"
        self.assertEqual([], SCAN.scan_blob("Demo.kt", extension, ()))
        nested = b'val s = "${"person@' + b'audit.test"}"'
        self.assertIn(("Demo.kt", 1, "review-email"), SCAN.scan_blob("Demo.kt", nested, ()))
        interpolated_receiver = b'val s = "${this@' + b'Demo.member}"'
        self.assertEqual([], SCAN.scan_blob("Demo.kt", interpolated_receiver, ()))

    def test_git_checkout_does_not_require_ignored_build_attachments(self):
        self.assertTrue(PACKAGE.EXTRAS)
        self.assertFalse(any(name.startswith("artifacts/") for name in PACKAGE.FILES))

    def test_nested_archive_markers_are_checked(self):
        marker = b"synthetic-private-marker"
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as output:
            output.writestr("notes.txt", marker)
        self.assertIn(("source.zip!notes.txt", 1, "local-identity"),
                      SCAN.scan_blob("source.zip", archive.getvalue(), (marker,)))

    def test_source_boundary_excludes_private_and_generated_nested_files(self):
        with tempfile.TemporaryDirectory() as directory:
            previous = PACKAGE.ROOT
            PACKAGE.ROOT = Path(directory)
            self.addCleanup(setattr, PACKAGE, "ROOT", previous)
            forbidden = ("docs/evidence/raw.txt", "app/src/.env.local", "runtime/node_modules/a.js",
                         "scripts/.pi/session.json", "app/src/main/jniLibs/libsample.so",
                         "docs/archive.zip", "scripts/credentials.json", "runtime/signing.properties",
                         "docs/private/data.txt", "app/src/raw.har", "docs/example.sqlite",
                         "runtime/out/report.txt", "scripts/dist/report.txt", "docs/artifacts/report.txt",
                         "runtime/module.pyd")
            required = ("gradle/wrapper/gradle-wrapper.jar", "runtime/patches/example.patch",
                        "app/src/main/res/values/strings.xml", "licenses/LICENSE.txt")
            for name in forbidden + required:
                path = PACKAGE.ROOT / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("synthetic")
                self.assertEqual(name in required, PACKAGE.public_file(path), name)

    def test_git_ignore_excludes_outputs_but_keeps_build_and_legal_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shutil.copy2(ROOT / ".gitignore", root / ".gitignore")
            env = {**os.environ, "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull}
            subprocess.run(["git", "init", "-q", directory], check=True, env=env, capture_output=True)
            ignored = (".build/raw.txt", "artifacts/app.apk", "app/build/result.txt", "local.properties",
                       "docs/evidence/old.png", "keys/release.jks", "src/.env.production", "src/.npmrc",
                       "sessions/account.json", "userdata/config.json", "workspaces/project.txt",
                       "nested/node_modules/library.js", "capture.har", "state/account.sqlite",
                       "signing.properties", ".pi/session.json", "archive.zip")
            kept = ("README.md", "AGENTS.md", "gradle/wrapper/gradle-wrapper.jar", "gradle.properties",
                    "runtime/patches/example.patch", "licenses/LICENSE.txt",
                    "app/src/main/res/values/strings.xml", "docs/TEST-REPORT.md")
            result = subprocess.run(["git", "check-ignore", "--no-index", "--stdin"], cwd=root,
                                    env=env, input="\n".join(ignored + kept) + "\n", text=True, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(set(ignored), set(result.stdout.splitlines()))

    def test_email_in_archive_member_name_is_not_exempt(self):
        name = "docs/person@" + "audit.test.txt"
        self.assertIn((name, 0, "review-email"), SCAN.scan_blob(name, b"clean", ()))
        self.assertNotIn("person@", SCAN.safe_name(name, ()))

    def test_apk_zip_members_are_inspected(self):
        marker = b"synthetic-private-marker"
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as output:
            output.writestr("classes.dex", b"binary\0" + marker)
        self.assertIn(("app.apk!classes.dex", 1, "local-identity"),
                      SCAN.scan_blob("app.apk", archive.getvalue(), (marker,)))

    def test_tar_identity_metadata_is_checked_for_all_regular_entries(self):
        marker = b"synthetic-private-owner"
        archive = io.BytesIO()
        with tarfile.open(fileobj=archive, mode="w:gz") as output:
            entry = tarfile.TarInfo("clean.txt")
            entry.uname = marker.decode()
            entry.gname = marker.decode()
            entry.pax_headers = {"custom": marker.decode()}
            entry.size = 5
            output.addfile(entry, io.BytesIO(b"clean"))
        self.assertIn(("source.tar.gz", 0, "local-identity"),
                      SCAN.scan_blob("source.tar.gz", archive.getvalue(), (marker,)))

    def test_native_attachment_exempts_only_upstream_attribution(self):
        archive = io.BytesIO()
        mail = b"contributor@" + b"upstream.org"
        with tarfile.open(fileobj=archive, mode="w:gz") as output:
            for name in ("runtime/build.sh", "./.build/sources/termux-proot/src/public.c"):
                entry = tarfile.TarInfo(name)
                entry.size = len(mail)
                output.addfile(entry, io.BytesIO(mail))
        findings = SCAN.scan_blob("Tanuki-native-corresponding-sources.tar.gz", archive.getvalue(), ())
        self.assertTrue(any(name.endswith("!runtime/build.sh") and rule == "review-email"
                            for name, _, rule in findings))
        self.assertFalse(any("public.c" in name for name, _, _ in findings))

    def test_archive_budget_is_shared_with_nested_archives(self):
        inner = io.BytesIO()
        with zipfile.ZipFile(inner, "w", compression=zipfile.ZIP_DEFLATED) as output:
            output.writestr("data.txt", b"x" * 800)
        outer = io.BytesIO()
        with zipfile.ZipFile(outer, "w") as output:
            output.writestr("nested.zip", inner.getvalue())
            output.writestr("more.txt", b"y" * 200)
        findings = SCAN.scan_blob("outer.zip", outer.getvalue(), (), budget=SCAN.ArchiveBudget(max_bytes=1000))
        self.assertTrue(any(rule == "archive-limit" for _, _, rule in findings))

    def make_git_fixture(self, directory):
        root = Path(directory)
        (root / "scripts").mkdir()
        (root / "docs").mkdir()
        for name in ("check-publication.py", "package-source.py"):
            shutil.copy2(ROOT / "scripts" / name, root / "scripts" / name)
        shutil.copy2(ROOT / ".gitignore", root / ".gitignore")
        home = root / "synthetic-private-owner"
        home.mkdir()
        env = {**os.environ, "HOME": str(home), "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull,
               "PYTHONDONTWRITEBYTECODE": "1"}
        subprocess.run(["git", "init", "-q", directory], check=True, env=env, capture_output=True)
        return root, env

    def test_index_secret_is_found_when_worktree_has_been_cleaned(self):
        with tempfile.TemporaryDirectory() as directory:
            root, env = self.make_git_fixture(directory)
            fake = "sk-" + "x" * 32
            (root / "README.md").write_text(fake)
            subprocess.run(["git", "add", "README.md"], cwd=root, env=env, check=True, capture_output=True)
            (root / "README.md").write_text("clean working tree")
            result = subprocess.run([sys.executable, "scripts/check-publication.py", "--git"], cwd=root,
                                    env=env, text=True, capture_output=True)
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("[index] README.md:1: api-key", result.stdout)
            self.assertNotIn(fake, result.stdout + result.stderr)

    def test_stdout_redacts_credentials_and_case_variants_in_filenames(self):
        with tempfile.TemporaryDirectory() as directory:
            root, env = self.make_git_fixture(directory)
            fake = "sk-" + "x" * 32
            marker = Path(env["HOME"]).name.upper()
            (root / "docs" / (fake + ".txt")).write_text("synthetic")
            (root / "docs" / (marker + ".txt")).write_text("synthetic")
            result = subprocess.run([sys.executable, "scripts/check-publication.py", "--git"], cwd=root,
                                    env=env, text=True, capture_output=True)
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("<credential>", result.stdout)
            self.assertIn("<private>", result.stdout)
            self.assertNotIn(fake, result.stdout + result.stderr)
            self.assertNotIn(marker, result.stdout + result.stderr)

    def test_local_markdown_links_survive_document_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            readme = root / "README.md"
            (root / "docs").mkdir()
            (root / "docs/keep.md").write_text("# Kept\n")
            readme.write_text("[kept](docs/keep.md#section) [web](https://example.com) [gone](docs/gone.md)\n")
            self.assertEqual([("README.md", 1, "broken-local-link")], SCAN.link_findings(root, [readme]))


if __name__ == "__main__":
    unittest.main(verbosity=2)
