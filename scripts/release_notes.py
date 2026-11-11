#!/usr/bin/env python3
"""Prepare public app features from the README for an APK Release."""
import argparse
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
FEATURE_SECTIONS = (
    "Conta e biblioteca",
    "Aparência e temas",
    "Idiomas do aplicativo",
    "Transmissão e fila",
    "Imagem e tela",
    "Controles e periféricos",
    "Touch nativo",
    "Controles de toque",
    "Áudio e microfone",
    "Rede e preferências",
    "Estatísticas e diagnósticos",
    "Tela inicial da TV",
    "Compatibilidade",
)


def release_notes(readme, version):
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError("Use uma versão no formato 0.32.0.")
    sections = {name: [] for name in FEATURE_SECTIONS}
    current = None
    for line in readme.splitlines():
        if line.startswith("## "):
            current = sections.get(line[3:].strip())
        elif current is not None and line.startswith(("- ", "|")):
            current.append(line)
    if any(not lines for lines in sections.values()):
        raise ValueError("O README precisa descrever todas as categorias do aplicativo.")
    parts = [
        "Cliente independente de Xbox Cloud Gaming para Android, Android TV e Google TV.",
        f"**Instalação ou atualização:** baixe **xCLOUD-{version}.apk** nos arquivos desta Release. "
        "Requer Android 7.0 ou superior.",
        "A disponibilidade dos jogos e dos recursos de transmissão depende da conta, "
        "da região, da assinatura ou licença, do aparelho e do serviço Xbox.",
    ]
    for name, lines in sections.items():
        block = [f"### {name}", ""]
        for index, line in enumerate(lines):
            if index and line.startswith("|") != lines[index - 1].startswith("|"):
                block.append("")
            block.append(line)
        parts.append("\n".join(block))
    parts.append("Código original sob licença MIT. Bibliotecas e componentes de terceiros "
                 "mantêm suas próprias licenças. Cliente independente, sem afiliação com "
                 "Microsoft ou Xbox; nomes, marcas e artes dos jogos pertencem aos seus titulares.")
    return "\n\n".join(parts) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--readme", type=Path, default=ROOT / "README.md")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.write_text(release_notes(args.readme.read_text(), args.version))


if __name__ == "__main__":
    main()
