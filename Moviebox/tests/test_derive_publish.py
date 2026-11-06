#!/usr/bin/env python3
"""Regresi derive_publish.py — PUBLISH_MODULES diturunkan dari kenaikan version.

Dijalankan di CI bersama test lain. Tidak menyentuh repo: setiap kasus memakai
katalog tiruan di tmpdir dan memonkeypatch pembaca versi modul.
"""
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import derive_publish as dp                                   # noqa: E402


def katalog(tmp, entries):
    folder = Path(tmp)
    (folder / "plugins.json").write_text(json.dumps(
        [{"internalName": n, "version": v} for n, v in entries.items()]),
        encoding="utf-8")
    return str(folder)


class DerivePublishTest(unittest.TestCase):
    def setUp(self):
        self.asli = dp.module_version
        self.root_asli = dp.ROOT
        self._env = os.environ.get("PUBLISH_MODULES")
        os.environ.pop("PUBLISH_MODULES", None)
        self._tmproot = tempfile.TemporaryDirectory()

    def tearDown(self):
        dp.module_version = self.asli
        dp.ROOT = self.root_asli
        self._tmproot.cleanup()
        if self._env is None:
            os.environ.pop("PUBLISH_MODULES", None)
        else:
            os.environ["PUBLISH_MODULES"] = self._env

    def pasang(self, lokal):
        """ROOT tiruan berisi <Modul>/build.gradle.kts nyata, supaya glob()
        dan pembaca versi diuji apa adanya (bukan di-monkeypatch)."""
        root = Path(self._tmproot.name)
        for module, version in lokal.items():
            folder = root / module
            folder.mkdir(parents=True, exist_ok=True)
            (folder / "build.gradle.kts").write_text(
                "// use an integer for version numbers\nversion = %d\n\n"
                "cloudstream {\n    language = \"id\"\n}\n" % version,
                encoding="utf-8")
        dp.ROOT = root

    def test_hanya_modul_yang_naik(self):
        self.pasang({"Dubbindo": 3, "Animasu": 5, "Pencuri": 2})
        with tempfile.TemporaryDirectory() as tmp:
            folder = katalog(tmp, {"Dubbindo": 2, "Animasu": 5, "Pencuri": 2})
            self.assertEqual(dp.derive(folder), ["Dubbindo"])

    def test_tidak_ada_kenaikan_berarti_kosong(self):
        """Menjalankan ulang commit yang sama tidak menerbitkan apa pun."""
        self.pasang({"Dubbindo": 3, "Animasu": 5})
        with tempfile.TemporaryDirectory() as tmp:
            folder = katalog(tmp, {"Dubbindo": 3, "Animasu": 5})
            self.assertEqual(dp.derive(folder), [])

    def test_modul_baru_ikut(self):
        self.pasang({"Dubbindo": 3, "ProviderBaru": 1})
        with tempfile.TemporaryDirectory() as tmp:
            folder = katalog(tmp, {"Dubbindo": 3})
            self.assertEqual(dp.derive(folder), ["ProviderBaru"])

    def test_moviebox_tidak_pernah_ikut(self):
        """Moviebox punya jalur sendiri; publish-extra menolaknya."""
        self.pasang({"Moviebox": 99, "Dubbindo": 3})
        with tempfile.TemporaryDirectory() as tmp:
            folder = katalog(tmp, {"Moviebox": 1, "Dubbindo": 3})
            self.assertEqual(dp.derive(folder), [])

    def test_version_turun_tidak_ikut(self):
        self.pasang({"Dubbindo": 1})
        with tempfile.TemporaryDirectory() as tmp:
            folder = katalog(tmp, {"Dubbindo": 3})
            self.assertEqual(dp.derive(folder), [])

    def test_env_manual_menang(self):
        """Pelarian darurat operator tetap ada."""
        self.pasang({"Dubbindo": 3})
        with tempfile.TemporaryDirectory() as tmp:
            folder = katalog(tmp, {"Dubbindo": 2})
            os.environ["PUBLISH_MODULES"] = "Animasu"
            manual = [n for n in os.environ.get("PUBLISH_MODULES", "").split() if n]
            self.assertEqual(manual or dp.derive(folder), ["Animasu"])

    def test_katalog_hilang_tidak_meledak(self):
        self.pasang({"Dubbindo": 3})
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(dp.derive(tmp), ["Dubbindo"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
