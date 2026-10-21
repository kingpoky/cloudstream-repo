#!/usr/bin/env python3
"""Publish Moviebox and ordinary modules while preserving every other released file and entry.

Perintah: snapshot, prepare (Moviebox), publish-extra (PUBLISH_MODULES),
remove-modules (REMOVE_MODULES), verify. Moviebox dilindungi dari jalur
modul biasa. Jalur retain-adi dihapus bersama modul Adi pada 20/10/2026.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import zipfile
import zlib

import os

ROOT = Path(__file__).resolve().parents[2]
# Modul yang tidak boleh diterbitkan/dihapus lewat jalur modul biasa.
PROTECTED = {"Moviebox"}
# Fork-aware: CI menyuplai GITHUB_REPOSITORY. Default tetap upstream agar
# pemeriksaan ini tidak melemah saat dijalankan manual di luar Actions.
REPOSITORY = os.environ.get("GITHUB_REPOSITORY") or "bikinveo-hash/RepairPremium_Repo"
# Modul biasa (non-Adi, non-Moviebox) yang boleh berubah byte pada publikasi ini.
# Kosong = guard tetap seketat semula; setiap modul di sini tetap wajib lulus
# verify_package, kenaikan version, dan paritas hash terhadap hasil build.
EXTRA = tuple(name for name in os.environ.get("PUBLISH_MODULES", "").split() if name)
# Modul yang sengaja DIHAPUS dari katalog pada publikasi ini. Kosong = guard
# identitas katalog tetap seketat semula (tidak ada entri boleh hilang).
REMOVED = tuple(name for name in os.environ.get("REMOVE_MODULES", "").split() if name)
MOVIEBOX_CLASSES = {
    "Lcom/Moviebox/" + name + ";" for name in (
        "MovieBoxProvider", "MovieboxPlugin", "MovieboxPlayback",
        "MovieboxPlaybackContext", "MovieboxEpisodes",
    )
}


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def catalog(rows):
    result = {row["internalName"]: row for row in rows}
    assert len(rows) == len(result), "Duplicate catalog identity"
    assert all(re.fullmatch(r"[A-Za-z0-9_-]+", name) for name in result), "Invalid catalog identity"
    return result


def dex_classes(data):
    assert len(data) >= 112 and data[:4] == b"dex\n" and data[7] == 0, "Invalid DEX header"
    assert struct.unpack_from("<I", data, 32)[0] == len(data), "DEX length mismatch"
    assert struct.unpack_from("<I", data, 40)[0] == 0x12345678, "Invalid DEX byte order"
    assert data[12:32] == hashlib.sha1(data[32:]).digest(), "Invalid DEX signature"
    assert struct.unpack_from("<I", data, 8)[0] == zlib.adler32(data[12:]) & 0xFFFFFFFF, "Invalid DEX checksum"
    count, offset = struct.unpack_from("<II", data, 56)
    strings = []
    for index in range(count):
        start = struct.unpack_from("<I", data, offset + index * 4)[0]
        while data[start] & 0x80:
            start += 1
        start += 1
        # DEX strings use modified UTF-8 (including C0 80 for NUL). Class
        # descriptors compared below are ASCII; unrelated string constants can
        # contain modified encodings and must not reject an otherwise valid DEX.
        strings.append(data[start:data.index(b"\0", start)].decode("utf-8", errors="replace"))
    count, offset = struct.unpack_from("<II", data, 64)
    types = [strings[struct.unpack_from("<I", data, offset + index * 4)[0]] for index in range(count)]
    count, offset = struct.unpack_from("<II", data, 96)
    classes = [types[struct.unpack_from("<I", data, offset + index * 32)[0]] for index in range(count)]
    assert len(classes) == len(set(classes)), "Duplicate DEX class"
    return classes


def verify_package(path, row, moviebox=False):
    assert path.stat().st_size == row["fileSize"], "Artifact size mismatch: " + path.name
    assert row["fileHash"] == "sha256-" + sha256(path), "Artifact hash mismatch: " + path.name
    assert row["url"] == f"https://raw.githubusercontent.com/{REPOSITORY}/builds/{row['internalName']}.cs3", "Incorrect artifact URL"
    with zipfile.ZipFile(path) as archive:
        assert archive.testzip() is None, "Corrupt ZIP"
        manifest = json.loads(archive.read("manifest.json"))
        assert manifest["name"] == row["internalName"] and manifest["version"] == row["version"], "Manifest mismatch"
        classes = dex_classes(archive.read("classes.dex"))
        if moviebox:
            assert manifest["pluginClassName"] == "com.Moviebox.MovieboxPlugin", "Incorrect Moviebox plugin class"
            assert MOVIEBOX_CLASSES <= set(classes), "Missing Moviebox production classes"
            assert [name for name in classes if name.endswith("Plugin;")] == ["Lcom/Moviebox/MovieboxPlugin;"], "Duplicate plugin entry"
            assert not any(name.startswith(("Lcom/AdiDrakor/", "Lcom/michat88/", "Lcom/adixtream/", "Lcom/Adicinemax21/")) for name in classes), "Adi dependency in standalone Moviebox"
            assert not any("Regression" in name or "Integration" in name or name.startswith("Landroid/") for name in classes), "Test classes packaged"
        assert not any(name.startswith("com/Moviebox/tests/") or name.endswith(("Regression.class", "Integration.class")) for name in archive.namelist()), "Test classes packaged"
        return manifest


def inventory(folder):
    return {
        str(path.relative_to(folder)): sha256(path)
        for path in sorted(folder.rglob("*"))
        if path.is_file() and ".git" not in path.relative_to(folder).parts
    }


def verify_catalog(folder):
    rows = catalog(json.loads((folder / "plugins.json").read_text()))
    for name, row in rows.items():
        verify_package(folder / (name + ".cs3"), row, moviebox=name == "Moviebox" and row["version"] >= 20)
    return rows


def snapshot(folder, state_file):
    rows = verify_catalog(folder)
    assert PROTECTED <= set(rows), "Missing protected catalog entries"
    state_file.write_text(json.dumps({"catalog": list(rows.values()), "files": inventory(folder)}, indent=2) + "\n")
    print("PASS publication baseline:", len(rows), "plugins")


def preserved(folder, state, allow_moviebox):
    before = catalog(state["catalog"])
    after = verify_catalog(folder)
    assert not (set(REMOVED) & PROTECTED), "Modul terlindungi tidak boleh dihapus lewat jalur ini"
    # Modul di EXTRA yang benar-benar baru MUNCUL di distribusi (bukan hanya
    # disebut di EXTRA) adalah modul baru yang sah ditambahkan lewat
    # PUBLISH_MODULES. Irisan dengan (after - before), bukan EXTRA mentah --
    # EXTRA adalah env var module-level yang terbaca di SEMUA pemanggilan
    # (termasuk saat unittest lain jalan di proses CI yang sama), jadi harus
    # dibuktikan dulu modul itu nyata sebelum dianggap "baru yang sah".
    new_modules = set(EXTRA) & (set(after) - set(before))
    assert (set(before) - set(REMOVED)) | new_modules == set(after), "Catalog identities changed"
    mutable = set(EXTRA) | ({"Moviebox"} if allow_moviebox else set())
    for name in before:
        if name not in mutable and name not in REMOVED:
            assert before[name] == after[name], "Protected catalog entry changed: " + name
    files = inventory(folder)
    removed_files = {name + ".cs3" for name in REMOVED}
    new_files = {name + ".cs3" for name in new_modules}
    assert files.keys() == (state["files"].keys() - removed_files) | new_files, "Published file inventory changed"
    allowed = {"plugins.json"} | {name + ".cs3" for name in mutable}
    for path, digest in state["files"].items():
        if path not in allowed and path not in removed_files:
            assert files[path] == digest, "Protected published file changed: " + path
    return before, after


def publish_extra(folder, state):
    """Terbitkan modul biasa di PUBLISH_MODULES dengan gate sekeras jalur Moviebox.

    Dijalankan SETELAH tahap Moviebox. Basis katalog diambil dari plugins.json
    yang sedang berada di distribusi, bukan dari snapshot awal, supaya publikasi
    Moviebox pada run yang sama tidak terbalik.
    """
    assert EXTRA, "PUBLISH_MODULES kosong"
    assert not (set(EXTRA) & PROTECTED), "Modul terlindungi tidak boleh lewat jalur ini"
    before = catalog(state["catalog"])
    current = catalog(json.loads((folder / "plugins.json").read_text()))
    generated = catalog(json.loads((ROOT / "build/plugins.json").read_text()))
    staged = []
    for name in EXTRA:
        row = generated[name]
        source = ROOT / name / "build" / (name + ".cs3")
        verify_package(source, row)
        version = int(re.search(r"^version\s*=\s*(\d+)", (ROOT / name / "build.gradle.kts").read_text(), re.M)[1])
        assert row["version"] == version, "Generated version differs from source: " + name
        if name in before:
            assert row["version"] >= before[name]["version"], "Version downgrade: " + name
            if row["version"] == before[name]["version"]:
                assert row["fileHash"] == before[name]["fileHash"], "Changed package needs a version increase: " + name
        staged.append((name, row, source))
    # Semua pemeriksaan selesai sebelum distribusi disentuh.
    rows = {name: row for name, row, _ in staged}
    for name, _, source in staged:
        temporary = folder / (name + ".cs3.pending")
        shutil.copyfile(source, temporary)
        temporary.replace(folder / (name + ".cs3"))
    # current.items() hanya memuat entri yang SUDAH ada di distribusi -- modul
    # baru (pertama kali lewat EXTRA) tidak akan pernah muncul lewat jalur itu,
    # jadi entri baru ditambahkan eksplisit di akhir.
    merged = dict(current)
    merged.update(rows)
    temporary = folder / "plugins.json.pending"
    temporary.write_text(json.dumps([rows.get(name, row) for name, row in merged.items()], indent=2) + "\n")
    temporary.replace(folder / "plugins.json")
    after = verify_catalog(folder)
    for name, row, source in staged:
        assert after[name] == row, "Publication differs from generated metadata: " + name
        assert sha256(folder / (name + ".cs3")) == sha256(source), "Published bytes differ from build: " + name
    preserved(folder, state, allow_moviebox=True)
    print("PASS published modules:", ", ".join(f"{n} v{r['version']}" for n, r, _ in staged))
    print("PASS every other catalog entry and published byte unchanged")


def remove_modules(folder, state):
    """Hapus modul di REMOVE_MODULES dari katalog dan distribusi.

    Dijalankan SEBELUM tahap Adi/Moviebox supaya snapshot berikutnya konsisten.
    Modul terlindungi tidak boleh lewat sini.
    """
    assert REMOVED, "REMOVE_MODULES kosong"
    assert not (set(REMOVED) & PROTECTED), "Modul terlindungi tidak boleh dihapus lewat jalur ini"
    before = catalog(state["catalog"])
    assert set(REMOVED) <= set(before), "REMOVE_MODULES menyebut entri yang tidak ada di katalog"
    for name in REMOVED:
        assert not (ROOT / name).exists(), "Source modul masih ada, hapus dari repo dulu: " + name
    rows = [row for row in state["catalog"] if row["internalName"] not in REMOVED]
    temporary = folder / "plugins.json.pending"
    temporary.write_text(json.dumps(rows, indent=2) + "\n")
    temporary.replace(folder / "plugins.json")
    for name in REMOVED:
        target = folder / (name + ".cs3")
        if target.exists():
            target.unlink()
    preserved(folder, state, allow_moviebox=False)
    print("PASS removed from catalog:", ", ".join(sorted(REMOVED)))
    print("PASS remaining entries:", len(rows))


def prepare(folder, state):
    before, _ = preserved(folder, state, allow_moviebox=False)
    generated = catalog(json.loads((ROOT / "build/plugins.json").read_text()))
    row = generated["Moviebox"]
    source = ROOT / "Moviebox/build/Moviebox.cs3"
    verify_package(source, row, moviebox=True)
    version = int(re.search(r"^version\s*=\s*(\d+)", (ROOT / "Moviebox/build.gradle.kts").read_text(), re.M)[1])
    assert row["version"] == version, "Generated version differs from source"
    assert row["version"] >= before["Moviebox"]["version"], "Moviebox version downgrade"
    if row["version"] == before["Moviebox"]["version"]:
        assert row["fileHash"] == before["Moviebox"]["fileHash"], "Changed Moviebox needs a version increase"
    # All checks happen before changing the disposable distribution checkout.
    temporary = folder / "Moviebox.cs3.pending"
    shutil.copyfile(source, temporary)
    temporary.replace(folder / "Moviebox.cs3")
    rows = [row if old["internalName"] == "Moviebox" else old for old in state["catalog"]]
    temporary = folder / "plugins.json.pending"
    temporary.write_text(json.dumps(rows, indent=2) + "\n")
    temporary.replace(folder / "plugins.json")
    verify(folder, state)


def verify(folder, state):
    before, after = preserved(folder, state, allow_moviebox=True)
    row = after["Moviebox"]
    assert row["version"] >= before["Moviebox"]["version"], "Moviebox version downgrade"
    if row["version"] == before["Moviebox"]["version"]:
        assert row["fileHash"] == before["Moviebox"]["fileHash"], "Changed Moviebox needs a version increase"
    version = int(re.search(r"^version\s*=\s*(\d+)", (ROOT / "Moviebox/build.gradle.kts").read_text(), re.M)[1])
    assert row["version"] == version, "Published version differs from source"
    verify_package(folder / "Moviebox.cs3", row, moviebox=True)
    generated = catalog(json.loads((ROOT / "build/plugins.json").read_text()))["Moviebox"]
    assert row == generated, "Moviebox publication differs from generated metadata"
    assert sha256(folder / "Moviebox.cs3") == sha256(ROOT / "Moviebox/build/Moviebox.cs3"), "Published Moviebox differs from build"
    print("PASS final catalog:", len(after), "plugins; Moviebox version", row["version"])
    print("PASS all non-Moviebox entries and published bytes unchanged")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["snapshot", "prepare", "verify",
                                            "publish-extra", "remove-modules"])
    parser.add_argument("distribution", type=Path)
    parser.add_argument("state", type=Path)
    args = parser.parse_args()
    if args.command == "snapshot":
        snapshot(args.distribution, args.state)
    else:
        state = json.loads(args.state.read_text())
        {"prepare": prepare, "verify": verify,
         "publish-extra": publish_extra,
         "remove-modules": remove_modules}[args.command](args.distribution, state)


if __name__ == "__main__":
    main()
