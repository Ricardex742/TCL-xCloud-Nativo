#!/usr/bin/env python3
"""Prepare explicitly supplied release additions without copying the README."""
import argparse
from pathlib import Path
import re

def release_notes(notes, version):
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError("Use uma versão no formato 0.35.0.")
    body = notes.strip()
    if not body:
        raise ValueError("Descreva somente as novidades desta versão.")
    return body + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--notes-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.write_text(release_notes(args.notes_file.read_text(encoding="utf-8"), args.version), encoding="utf-8")


if __name__ == "__main__":
    main()
