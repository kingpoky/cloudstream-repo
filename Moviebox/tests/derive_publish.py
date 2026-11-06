#!/usr/bin/env python3
"""Turunkan PUBLISH_MODULES dari kenaikan `version`, bukan dari env manual.

Alasan: jalur lama menuntut dua commit (isi env, lalu kosongkan lagi). Kalau
publikasi berjalan unattended dan gagal di tengah, env tertinggal terisi dan
run BERIKUTNYA gagal total karena modul itu sudah tidak punya kenaikan versi.
Menurunkannya dari state membuat langkah itu idempotent: tidak ada yang perlu
dibersihkan, dan menjalankan ulang commit yang sama tidak menerbitkan apa pun.

Aturan: modul biasa diterbitkan bila `version` di <Modul>/build.gradle.kts
LEBIH BESAR daripada `version` entri katalog `builds/plugins.json`. Modul yang
belum ada di katalog (modul baru) ikut diterbitkan.

Env manual tetap menang bila diisi — pelarian darurat untuk operator.
Mencetak satu baris `PUBLISH_MODULES=...` ke stdout untuk $GITHUB_ENV.
"""
import json
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROTECTED = {"Moviebox"}
VERSION_RE = re.compile(r"^\s*version\s*=\s*(\d+)\s*$", re.M)


def module_version(module):
    gradle = ROOT / module / "build.gradle.kts"
    if not gradle.is_file():
        return None
    match = VERSION_RE.search(gradle.read_text(encoding="utf-8"))
    return int(match.group(1)) if match else None


def catalog_versions(builds):
    plugins = Path(builds) / "plugins.json"
    if not plugins.is_file():
        return {}
    rows = json.loads(plugins.read_text(encoding="utf-8"))
    return {row["internalName"]: int(row.get("version", 0)) for row in rows}


def derive(builds):
    published = catalog_versions(builds)
    out = []
    for gradle in sorted(ROOT.glob("*/build.gradle.kts")):
        module = gradle.parent.name
        if module in PROTECTED:
            continue
        local = module_version(module)
        if local is None:
            continue
        if local > published.get(module, 0):
            out.append(module)
    return out


def main():
    builds = sys.argv[1]
    manual = [n for n in os.environ.get("PUBLISH_MODULES", "").split() if n]
    modules = manual or derive(builds)
    # Baris ini dibaca GitHub Actions lewat $GITHUB_ENV; catatan ke stderr.
    print("PUBLISH_MODULES=" + " ".join(modules))
    print("derive_publish: manual=%s derived=%s" % (manual or "-", modules or "-"),
          file=sys.stderr)


if __name__ == "__main__":
    main()
