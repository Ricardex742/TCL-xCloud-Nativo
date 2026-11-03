#!/usr/bin/env python3
"""Restore the private, persistent debug key and validate its public certificate.

The keystore is a GitHub Actions secret, never a repository file or public artifact.
The standard debug alias/password are intentionally public; the keystore must stay private.
"""
import argparse
import base64
import binascii
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
SECRET_NAME = "XCLOUD_ANDROID_KEYSTORE_BASE64"


class SigningError(Exception):
    pass


def expected_certificate(path=ROOT / "signing/certificate-sha256.txt"):
    value = Path(path).read_text().strip().lower()
    if len(value) != 64 or any(char not in "0123456789abcdef" for char in value):
        raise SigningError("Fingerprint público da assinatura inválido.")
    return value


def keytool_path():
    java_home = os.environ.get("JAVA_HOME")
    return str(Path(java_home) / "bin/keytool") if java_home else (shutil.which("keytool") or "keytool")


def prepare(encoded, output_dir, certificate_file=ROOT / "signing/certificate-sha256.txt"):
    if not encoded or not encoded.strip():
        raise SigningError(f"Cadastre o segredo {SECRET_NAME} no GitHub. Nenhuma chave temporária será gerada.")
    try:
        data = base64.b64decode(encoded.strip(), validate=True)
    except (ValueError, binascii.Error):
        raise SigningError("O segredo de assinatura não contém base64 válido.") from None
    if not data or len(data) > 32768:
        raise SigningError("Tamanho inválido do keystore de assinatura.")
    folder = Path(output_dir)
    folder.mkdir(parents=True, exist_ok=True, mode=0o700)
    folder.chmod(0o700)
    key = folder / "xcloud-debug.keystore"
    # Exclusive creation prevents overwriting another build's signing material.
    fd = os.open(key, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as handle:
        handle.write(data)
    store_type = "JKS" if data.startswith(bytes.fromhex("feedfeed")) else "PKCS12"
    try:
        result = subprocess.run([
            keytool_path(), "-exportcert", "-keystore", str(key), "-storetype", store_type,
            "-alias", "androiddebugkey", "-storepass:env", "XCLOUD_DEBUG_STOREPASS",
        ], env={**os.environ, "XCLOUD_DEBUG_STOREPASS": "android"}, capture_output=True)
        if result.returncode:
            raise SigningError("Não foi possível abrir a chave de assinatura configurada.")
        fingerprint = hashlib.sha256(result.stdout).hexdigest()
        if fingerprint != expected_certificate(certificate_file):
            raise SigningError("A chave configurada tem outro certificado. APK bloqueado para preservar as atualizações.")
    except Exception:
        key.unlink(missing_ok=True)
        raise
    return key, store_type, fingerprint


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--secret-file", type=Path, help="Arquivo privado base64 para builds locais; nunca será impresso.")
    args = parser.parse_args()
    encoded = args.secret_file.read_text() if args.secret_file else os.environ.get(SECRET_NAME, "")
    parent = os.environ.get("RUNNER_TEMP") or tempfile.gettempdir()
    folder = tempfile.mkdtemp(prefix="xcloud-signing-", dir=parent)
    try:
        key, store_type, fingerprint = prepare(encoded, folder)
    except (SigningError, OSError) as error:
        shutil.rmtree(folder, ignore_errors=True)
        raise SystemExit(str(error)) from None
    github_env = os.environ.get("GITHUB_ENV")
    if github_env:
        with open(github_env, "a") as handle:
            handle.write(f"XCLOUD_KEYSTORE_PATH={key}\nXCLOUD_KEYSTORE_TYPE={store_type}\n")
    # These values are paths/public certificate metadata, never private key bytes.
    print(f"XCLOUD_KEYSTORE_PATH={key}")
    print(f"XCLOUD_KEYSTORE_TYPE={store_type}")
    print(f"Certificado SHA-256 validado: {fingerprint}")


if __name__ == "__main__":
    main()
