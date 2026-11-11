#!/usr/bin/env python3
"""Reject APKs signed with any key other than the pinned xCLOUD update certificate."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

from prepare_signing import SigningError, expected_certificate


def signer_certificate(output, expected):
    matches = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})", output)
    if len(matches) != 1 or matches[0].lower() != expected:
        raise SigningError("Assinatura do APK diferente da identidade fixa. Publicação bloqueada.")
    return matches[0].lower()


def package_metadata(output):
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", output)
    if not match or match[1] != "com.xcloud.tclnative":
        raise SigningError("Identificador do aplicativo alterado; o APK não atualizaria a instalação existente.")
    if int(match[2]) < 16:
        raise SigningError("versionCode anterior à correção de assinatura. Publicação bloqueada.")
    return {"package": match[1], "versionCode": int(match[2]), "versionName": match[3]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise SystemExit("Configure ANDROID_HOME para verificar o APK.")
    build_tools = Path(sdk) / "build-tools/34.0.0"
    signature = subprocess.run([str(build_tools / "apksigner"), "verify", "--print-certs", str(args.apk)],
                               capture_output=True, text=True)
    if signature.returncode:
        raise SystemExit("A assinatura do APK não passou na verificação criptográfica.")
    try:
        fingerprint = signer_certificate(signature.stdout, expected_certificate())
        badging = subprocess.run([str(build_tools / "aapt"), "dump", "badging", str(args.apk)],
                                 capture_output=True, text=True, check=True)
        metadata = package_metadata(badging.stdout)
    except (SigningError, subprocess.SubprocessError) as error:
        raise SystemExit(str(error)) from None
    metadata.update(signingCertificateSHA256=fingerprint, sha256=hashlib.sha256(args.apk.read_bytes()).hexdigest())
    args.apk.with_suffix(args.apk.suffix + ".metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps(metadata, ensure_ascii=False))


if __name__ == "__main__":
    main()
