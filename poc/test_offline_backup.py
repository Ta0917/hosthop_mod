import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import zipfile

import offline_backup as backup
import snapshot_manifest


class OfflineBackupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=Path(__file__).parent)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.world = self.root / "world"
        (self.world / "dimensions" / "mod").mkdir(parents=True)
        (self.world / "dimensions" / "mod" / "chunk.dat").write_bytes(b"modded chunks")
        (self.world / "level.dat").write_bytes(b"world settings")
        self.archive = self.root / "backup.zip"

    def test_round_trip_and_no_overwrite(self):
        self.assertEqual(2, backup.create_backup(self.world, self.archive))
        before = self.archive.read_bytes()
        with self.assertRaises(FileExistsError):
            backup.create_backup(self.world, self.archive)
        self.assertEqual(before, self.archive.read_bytes())
        restored = self.root / "restored"
        self.assertEqual(2, backup.restore_backup(self.archive, restored))
        self.assertEqual(snapshot_manifest.manifest(self.world), snapshot_manifest.manifest(restored))
        with self.assertRaises(FileExistsError):
            backup.restore_backup(self.archive, restored)

    def test_corruption_fails_before_restore(self):
        backup.create_backup(self.world, self.archive)
        with zipfile.ZipFile(self.archive) as source:
            manifest = source.read(backup.MANIFEST)
        corrupt = self.root / "corrupt.zip"
        with zipfile.ZipFile(corrupt, "w") as target:
            target.writestr(backup.MANIFEST, manifest)
            target.writestr("world/level.dat", b"world settingS")
            target.writestr("world/dimensions/mod/chunk.dat", b"modded chunks")
        restored = self.root / "restored"
        with self.assertRaises(ValueError):
            backup.restore_backup(corrupt, restored)
        self.assertFalse(restored.exists())
        self.assertEqual(2, backup.verify_backup(self.archive))

    def test_write_failure_preserves_prior_verified_backup(self):
        backup.create_backup(self.world, self.archive)
        before = self.archive.read_bytes()
        with mock.patch.object(backup, "_copy_verified", side_effect=OSError("simulated write failure")):
            with self.assertRaises(OSError):
                backup.create_backup(self.world, self.root / "failed.zip")
        self.assertEqual(before, self.archive.read_bytes())
        self.assertEqual(2, backup.verify_backup(self.archive))

    def test_rejects_traversal_before_restore(self):
        malicious = self.root / "malicious.zip"
        entry = {"format": 1, "files": {"../outside": {"size": 0, "sha256": "0" * 64}}}
        with zipfile.ZipFile(malicious, "w") as target:
            target.writestr(backup.MANIFEST, json.dumps(entry))
            target.writestr("world/../outside", b"")
        with self.assertRaises(ValueError):
            backup.restore_backup(malicious, self.root / "restored")
        self.assertFalse((self.root / "restored").exists())
        self.assertFalse((self.root / "outside").exists())

    def test_rejects_nonportable_paths(self):
        for name in (".", "../outside", "a/../b", "C:/data", "foo\\bar", "dir/CON.dat", "trailing.", "bad?file"):
            with self.subTest(name=name):
                self.assertFalse(backup._valid_path(name))

    def test_cannot_write_backup_inside_source(self):
        with self.assertRaises(ValueError):
            backup.create_backup(self.world, self.world / "backup.zip")
        self.assertFalse((self.world / "backup.zip").exists())

    def test_rejects_file_directory_conflict_before_restore(self):
        malicious = self.root / "conflict.zip"
        empty = {"size": 0, "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"}
        entry = {"format": 1, "files": {"folder": empty, "folder/file": empty}}
        with zipfile.ZipFile(malicious, "w") as target:
            target.writestr(backup.MANIFEST, json.dumps(entry))
            target.writestr("world/folder", b"")
            target.writestr("world/folder/file", b"")
        with self.assertRaises(ValueError):
            backup.restore_backup(malicious, self.root / "restored")
        self.assertFalse((self.root / "restored").exists())


if __name__ == "__main__":
    unittest.main()
