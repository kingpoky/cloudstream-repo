"""Deterministic release guards; synthetic DEX tables are not playback fixtures."""
import copy
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import zlib

import distribution as release


def dex_fixture(classes):
    """Minimal class/string tables for the release inspector, with valid checksums."""
    count = len(classes)
    strings_offset = 112
    types_offset = strings_offset + count * 4
    classes_offset = types_offset + count * 4
    data = bytearray(classes_offset + count * 32)
    data[:8] = b"dex\n035\0"
    struct.pack_into("<II", data, 36, 112, 0x12345678)
    struct.pack_into("<II", data, 56, count, strings_offset)
    struct.pack_into("<II", data, 64, count, types_offset)
    struct.pack_into("<II", data, 96, count, classes_offset)
    for index, name in enumerate(classes):
        struct.pack_into("<I", data, strings_offset + index * 4, len(data))
        struct.pack_into("<I", data, types_offset + index * 4, index)
        struct.pack_into("<I", data, classes_offset + index * 32, index)
        encoded = name.encode()
        assert len(encoded) < 128
        data.extend(bytes([len(encoded)]) + encoded + b"\0")
    struct.pack_into("<I", data, 32, len(data))
    data[12:32] = hashlib.sha1(data[32:]).digest()
    struct.pack_into("<I", data, 8, zlib.adler32(data[12:]) & 0xFFFFFFFF)
    return bytes(data)


def artifact(path, name, version, classes=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("manifest.json", json.dumps({"name": name, "version": version,
            "pluginClassName": "com.Moviebox.MovieboxPlugin" if name == "Moviebox" else "com.example." + name + "Plugin"}))
        archive.writestr("classes.dex", dex_fixture(sorted(classes or release.MOVIEBOX_CLASSES)))
    return {"internalName": name, "name": name, "version": version,
        "fileSize": path.stat().st_size, "fileHash": "sha256-" + release.sha256(path),
        "url": f"https://raw.githubusercontent.com/{release.REPOSITORY}/builds/{name}.cs3"}


class DistributionTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.folder = self.root / "published"
        self.folder.mkdir()
        self.before = [artifact(self.folder / (name + ".cs3"), name, 12)
            for name in sorted({"Moviebox", "Other", "Extra"})]
        (self.folder / "plugins.json").write_text(json.dumps(self.before))
        (self.folder / "repo.json").write_text('{"unchanged":true}')
        self.state = {"catalog": copy.deepcopy(self.before), "files": release.inventory(self.folder)}
        self.update = artifact(self.root / "Moviebox/build/Moviebox.cs3", "Moviebox", 20)
        (self.root / "Moviebox/build.gradle.kts").write_text("version = 20\n")
        (self.root / "build").mkdir()
        self.generated = [self.update if row["internalName"] == "Moviebox" else row for row in self.before]
        (self.root / "build/plugins.json").write_text(json.dumps(self.generated))
        self.patcher = patch.object(release, "ROOT", self.root)
        self.patcher.start()
        self.addCleanup(self.patcher.stop)

    def test_only_moviebox_bytes_and_metadata_change(self):
        release.prepare(self.folder, self.state)
        after = release.inventory(self.folder)
        self.assertEqual({path for path in after if after[path] != self.state["files"][path]}, {"Moviebox.cs3", "plugins.json"})
        release.verify(self.folder, self.state)

    def test_non_moviebox_metadata_change_rejected(self):
        self.before[0]["name"] = "changed"
        (self.folder / "plugins.json").write_text(json.dumps(self.before))
        with self.assertRaisesRegex(AssertionError, "Protected catalog"):
            release.prepare(self.folder, self.state)

    def test_non_moviebox_bytes_change_rejected(self):
        (self.folder / "repo.json").write_text("changed")
        with self.assertRaisesRegex(AssertionError, "Protected published"):
            release.prepare(self.folder, self.state)

    def test_missing_plugin_rejected(self):
        (self.folder / "plugins.json").write_text(json.dumps(self.before[:-1]))
        with self.assertRaisesRegex(AssertionError, "Catalog identities"):
            release.prepare(self.folder, self.state)

    def test_duplicate_plugin_rejected(self):
        with self.assertRaisesRegex(AssertionError, "Duplicate catalog"):
            release.catalog(self.before + self.before[:1])

    def test_extra_published_file_rejected(self):
        (self.folder / "unexpected.cs3").write_bytes(b"extra")
        with self.assertRaisesRegex(AssertionError, "inventory"):
            release.prepare(self.folder, self.state)

    def test_corrupt_artifact_rejected_before_staging(self):
        (self.root / "Moviebox/build/Moviebox.cs3").write_bytes(b"corrupt")
        with self.assertRaises(AssertionError):
            release.prepare(self.folder, self.state)
        self.assertEqual(release.inventory(self.folder), self.state["files"])

    def test_missing_new_class_rejected(self):
        row = artifact(self.root / "missing.cs3", "Moviebox", 20,
            release.MOVIEBOX_CLASSES - {"Lcom/Moviebox/MovieboxPlaybackContext;"})
        with self.assertRaisesRegex(AssertionError, "Missing Moviebox"):
            release.verify_package(self.root / "missing.cs3", row, moviebox=True)

    def test_second_plugin_class_rejected(self):
        row = artifact(self.root / "duplicate.cs3", "Moviebox", 20,
            release.MOVIEBOX_CLASSES | {"Lcom/legacy/OtherPlugin;"})
        with self.assertRaisesRegex(AssertionError, "Duplicate plugin"):
            release.verify_package(self.root / "duplicate.cs3", row, moviebox=True)

    def test_adi_dependency_rejected(self):
        row = artifact(self.root / "cross.cs3", "Moviebox", 20,
            release.MOVIEBOX_CLASSES | {"Lcom/AdiDrakor/Engine;"})
        with self.assertRaisesRegex(AssertionError, "Adi dependency"):
            release.verify_package(self.root / "cross.cs3", row, moviebox=True)

    def test_dex_checksum_rejected(self):
        data = bytearray(dex_fixture(sorted(release.MOVIEBOX_CLASSES)))
        data[-1] ^= 1
        with self.assertRaisesRegex(AssertionError, "signature"):
            release.dex_classes(data)

    def test_version_downgrade_rejected(self):
        self.state["catalog"] = [dict(row, version=21) if row["internalName"] == "Moviebox" else row for row in self.before]
        with patch.object(release, "preserved", return_value=(release.catalog(self.state["catalog"]), {})):
            with self.assertRaisesRegex(AssertionError, "downgrade"):
                release.prepare(self.folder, self.state)

    def test_same_version_different_bytes_rejected(self):
        self.state["catalog"] = [dict(row, version=20) if row["internalName"] == "Moviebox" else row for row in self.before]
        with patch.object(release, "preserved", return_value=(release.catalog(self.state["catalog"]), {})):
            with self.assertRaisesRegex(AssertionError, "version increase"):
                release.prepare(self.folder, self.state)

    def test_remove_modules_drops_entry_and_artifact(self):
        with patch.object(release, "REMOVED", ("Other",)):
            release.remove_modules(self.folder, self.state)
        rows = json.loads((self.folder / "plugins.json").read_text())
        self.assertEqual({row["internalName"] for row in rows}, {"Moviebox", "Extra"})
        self.assertFalse((self.folder / "Other.cs3").exists())
        self.assertTrue((self.folder / "Moviebox.cs3").exists())

    def test_remove_modules_refuses_protected(self):
        with patch.object(release, "REMOVED", ("Moviebox",)):
            with self.assertRaisesRegex(AssertionError, "terlindungi"):
                release.remove_modules(self.folder, self.state)

    def test_remove_modules_refuses_when_source_still_present(self):
        (self.root / "Other").mkdir()
        with patch.object(release, "REMOVED", ("Other",)):
            with self.assertRaisesRegex(AssertionError, "Source modul masih ada"):
                release.remove_modules(self.folder, self.state)

    def test_publish_extra_adds_new_module(self):
        """Provider pertama kali masuk lewat PUBLISH_MODULES (bukan update modul lama)."""
        new_row = artifact(self.root / "NewPlugin/build/NewPlugin.cs3", "NewPlugin", 1,
            {"Lcom/example/NewPluginPlugin;"})
        (self.root / "NewPlugin/build.gradle.kts").write_text("version = 1\n")
        generated = self.generated + [new_row]
        (self.root / "build/plugins.json").write_text(json.dumps(generated))
        with patch.object(release, "EXTRA", ("NewPlugin",)):
            release.publish_extra(self.folder, self.state)
        rows = json.loads((self.folder / "plugins.json").read_text())
        names = {row["internalName"] for row in rows}
        self.assertEqual(names, {"Moviebox", "Other", "Extra", "NewPlugin"})
        self.assertTrue((self.folder / "NewPlugin.cs3").exists())

    def test_publish_extra_updates_existing_module(self):
        """Modul lama yang SUDAH ada di katalog tetap kena gate versi/hash seperti biasa."""
        updated_row = artifact(self.root / "Extra/build/Extra.cs3", "Extra", 13)
        (self.root / "Extra/build.gradle.kts").write_text("version = 13\n")
        generated = [updated_row if row["internalName"] == "Extra" else row for row in self.generated]
        (self.root / "build/plugins.json").write_text(json.dumps(generated))
        with patch.object(release, "EXTRA", ("Extra",)):
            release.publish_extra(self.folder, self.state)
        rows = {row["internalName"]: row for row in json.loads((self.folder / "plugins.json").read_text())}
        self.assertEqual(rows["Extra"]["version"], 13)

    def test_publish_extra_rejects_existing_module_downgrade(self):
        """Modul lama tidak boleh turun versi lewat PUBLISH_MODULES, sama ketatnya spt Moviebox."""
        downgraded = artifact(self.root / "Extra/build/Extra.cs3", "Extra", 1)
        (self.root / "Extra/build.gradle.kts").write_text("version = 1\n")
        generated = [downgraded if row["internalName"] == "Extra" else row for row in self.generated]
        (self.root / "build/plugins.json").write_text(json.dumps(generated))
        with patch.object(release, "EXTRA", ("Extra",)):
            with self.assertRaisesRegex(AssertionError, "downgrade"):
                release.publish_extra(self.folder, self.state)


if __name__ == "__main__":
    unittest.main()
