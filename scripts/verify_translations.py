#!/usr/bin/env python3
"""Check offline language packs, message IDs and interpolation fields without network access."""
from pathlib import Path
import hashlib
import json
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
LOCALES = ("en-rUS", "es-rES", "fr-rFR", "de-rDE", "it-rIT", "ja-rJP", "ko-rKR", "zh-rCN", "ru-rRU", "tr-rTR")


def messages(path):
    result = {}
    for node in ET.parse(path).getroot().findall("string"):
        name = node.attrib["name"]
        assert name not in result, (path, "duplicate", name)
        value = node.text or ""
        assert value.startswith('"') and value.endswith('"'), (path, name, "preserve whitespace")
        value = re.sub(r"\\([nrt\\\"'])", lambda m: {"n": "\n", "r": "\r", "t": "\t"}.get(m[1], m[1]), value[1:-1])
        result[name] = value
    return result


def main():
    source = messages(RES / "values/ui_strings.xml")
    assert len(source) > 700, "Incomplete UI inventory"
    for name, text in source.items():
        assert name == "ui_" + hashlib.sha256(text.encode()).hexdigest()[:16], (name, "message ID changed")
    for path in (ROOT / "app/src/main/java").rglob("*.kt"):
        for match in re.finditer(r'UiText\.text\("((?:\\.|[^"\\])*)"', path.read_text()):
            text = json.loads('"' + match[1].replace("\\$", "$") + '"')
            name = "ui_" + hashlib.sha256(text.encode()).hexdigest()[:16]
            assert name in source, (path, "UI message missing from language packs", text)
    for locale in LOCALES:
        target = messages(RES / f"values-{locale}/ui_strings.xml")
        assert target.keys() == source.keys(), (locale, "missing or extra messages")
        assert sum(target[k] != v for k, v in source.items()) > len(source) // 2, (locale, "untranslated pack")
        for name, original in source.items():
            translated = target[name]
            assert translated.strip(), (locale, name, "empty translation")
            assert sorted(re.findall(r"\{\d+\}", translated)) == sorted(re.findall(r"\{\d+\}", original)), (locale, name, "interpolation fields")
            assert not re.search(r"ZXQ\d+QXZ", translated), (locale, name, "unfinished translation")
    print(f"Português + {len(LOCALES)} idiomas: {len(source)} mensagens por idioma; campos variáveis consistentes.")


if __name__ == "__main__":
    main()
