import base64
import hashlib
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from prepare_signing import SigningError, keytool_path, prepare
from verify_apk_signing import package_metadata, signer_certificate


class PersistentSigningTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.folder = Path(tempfile.mkdtemp(prefix="xcloud-signing-tests-"))
        cls.keys = []
        cls.fingerprints = []
        env = {**os.environ, "XCLOUD_TEST_PASSWORD": "android"}
        for index in range(2):
            key = cls.folder / f"test-{index}.keystore"
            result = subprocess.run([
                keytool_path(), "-genkeypair", "-keystore", str(key), "-storetype", "JKS",
                "-alias", "androiddebugkey", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "365", "-dname", "CN=Ephemeral Signing Test",
                "-storepass:env", "XCLOUD_TEST_PASSWORD", "-keypass:env", "XCLOUD_TEST_PASSWORD",
            ], env=env, capture_output=True)
            if result.returncode:
                raise AssertionError("Cannot generate ephemeral test key")
            exported = subprocess.run([
                keytool_path(), "-exportcert", "-keystore", str(key), "-alias", "androiddebugkey",
                "-storepass:env", "XCLOUD_TEST_PASSWORD",
            ], env=env, capture_output=True, check=True)
            cls.keys.append(base64.b64encode(key.read_bytes()).decode())
            cls.fingerprints.append(hashlib.sha256(exported.stdout).hexdigest())
        cls.pin = cls.folder / "certificate.txt"
        cls.pin.write_text(cls.fingerprints[0] + "\n")

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.folder)

    def setUp(self):
        self.output = Path(tempfile.mkdtemp(prefix="runner-", dir=self.folder))

    def test_missing_secret_never_creates_a_fallback_key(self):
        with self.assertRaises(SigningError):
            prepare("", self.output, self.pin)
        self.assertFalse((self.output / "xcloud-debug.keystore").exists())

    def test_invalid_base64_does_not_publish_private_input_in_the_error(self):
        invalid = "SENSITIVE_INVALID_VALUE!"
        with self.assertRaises(SigningError) as caught:
            prepare(invalid, self.output, self.pin)
        self.assertNotIn(invalid, str(caught.exception))

    def test_invalid_keystore_is_removed_without_exposing_its_bytes(self):
        with self.assertRaises(SigningError):
            prepare(base64.b64encode(b"not-a-keystore").decode(), self.output, self.pin)
        self.assertFalse((self.output / "xcloud-debug.keystore").exists())

    def test_different_private_key_is_rejected(self):
        with self.assertRaisesRegex(SigningError, "outro certificado"):
            prepare(self.keys[1], self.output, self.pin)
        self.assertFalse((self.output / "xcloud-debug.keystore").exists())

    def test_two_clean_runners_restore_the_same_identity_and_private_permissions(self):
        first = prepare(self.keys[0], self.output / "runner-a", self.pin)
        second = prepare(self.keys[0], self.output / "runner-b", self.pin)
        self.assertEqual(first[2], second[2])
        self.assertEqual(self.fingerprints[0], first[2])
        self.assertEqual(first[0].read_bytes(), second[0].read_bytes())
        self.assertEqual(0o600, stat.S_IMODE(first[0].stat().st_mode))
        self.assertEqual(0o700, stat.S_IMODE(first[0].parent.stat().st_mode))

    def test_apk_signature_check_rejects_wrong_or_multiple_signers(self):
        expected = self.fingerprints[0]
        valid = f"Signer #1 certificate SHA-256 digest: {expected}\n"
        self.assertEqual(expected, signer_certificate(valid, expected))
        with self.assertRaises(SigningError):
            signer_certificate(f"Signer #1 certificate SHA-256 digest: {self.fingerprints[1]}", expected)
        with self.assertRaises(SigningError):
            signer_certificate(valid + valid.replace("#1", "#2"), expected)

    def test_apk_package_and_version_preserve_the_update_identity(self):
        valid = "package: name='com.xcloud.tclnative' versionCode='16' versionName='0.16.0-stable-signing'"
        self.assertEqual(16, package_metadata(valid)["versionCode"])
        with self.assertRaises(SigningError):
            package_metadata(valid.replace("com.xcloud.tclnative", "com.other.package"))
        with self.assertRaises(SigningError):
            package_metadata(valid.replace("versionCode='16'", "versionCode='15'"))

    def test_repository_does_not_track_any_private_keystore_or_secret_export(self):
        tracked = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, capture_output=True, check=True).stdout.decode().split("\0")
        for name in filter(None, tracked):
            self.assertNotIn(Path(name).suffix.lower(), {".jks", ".keystore", ".p12", ".pfx"}, name)
            self.assertNotEqual("github-secret.txt", Path(name).name)


if __name__ == "__main__":
    unittest.main()
