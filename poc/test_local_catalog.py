import concurrent.futures
from pathlib import Path
import tempfile
import unittest
import zipfile

import lease_model
import local_catalog
import offline_backup
import snapshot_manifest


class LocalCatalogTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=Path(__file__).parent)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.store = self.root / "store"
        self.world = self.root / "world"
        self.world.mkdir()
        (self.world / "level.dat").write_bytes(b"first")
        self.archive = self.root / "archive.zip"
        offline_backup.create_backup(self.world, self.archive)
        local_catalog.initialize(self.store)
        self.db = self.store / "catalog.sqlite"

    def test_verified_generation_restores_to_new_directory(self):
        epoch, generation = lease_model.acquire(self.db, "host-a", 10, 10)
        digest = local_catalog.stage(self.store, self.archive)
        self.assertEqual((1,), local_catalog.commit(self.store, "host-a", epoch, 11, generation, digest))
        restored = self.root / "restored"
        self.assertEqual((1, 1), local_catalog.restore_latest(self.store, restored))
        self.assertEqual(snapshot_manifest.manifest(self.world), snapshot_manifest.manifest(restored))

    def test_incomplete_and_stale_upload_cannot_replace_committed_backup(self):
        epoch, generation = lease_model.acquire(self.db, "host-a", 10, 10)
        first = local_catalog.stage(self.store, self.archive)
        self.assertEqual((1,), local_catalog.commit(self.store, "host-a", epoch, 11, generation, first))
        broken = self.root / "broken.zip"
        broken.write_bytes(b"incomplete upload")
        with self.assertRaises(zipfile.BadZipFile):
            local_catalog.stage(self.store, broken)
        (self.world / "level.dat").write_bytes(b"second")
        newer_archive = self.root / "newer.zip"
        offline_backup.create_backup(self.world, newer_archive)
        newer = local_catalog.stage(self.store, newer_archive)
        new_epoch, gen = lease_model.acquire(self.db, "host-b", 20, 10)
        self.assertIsNone(local_catalog.commit(self.store, "host-a", epoch, 21, gen, newer))
        self.assertEqual(first, lease_model.state(self.db)[4])
        self.assertEqual((2,), local_catalog.commit(self.store, "host-b", new_epoch, 21, gen, newer))
        self.assertTrue((self.store / "objects" / (first + ".zip")).exists())

    def test_corruption_prevents_restore_and_commit(self):
        epoch, generation = lease_model.acquire(self.db, "host-a", 10, 10)
        digest = local_catalog.stage(self.store, self.archive)
        self.assertEqual((1,), local_catalog.commit(self.store, "host-a", epoch, 11, generation, digest))
        (self.store / "objects" / (digest + ".zip")).write_bytes(b"damaged")
        restored = self.root / "restored"
        with self.assertRaises(ValueError):
            local_catalog.restore_latest(self.store, restored)
        self.assertFalse(restored.exists())
        with self.assertRaises(ValueError):
            local_catalog.commit(self.store, "host-a", epoch, 12, 1, digest)

    def test_concurrent_publication_of_same_object(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(lambda _: local_catalog.stage(self.store, self.archive), range(8)))
        self.assertEqual(1, len(set(results)))
        self.assertEqual(1, len(list((self.store / "objects").iterdir())))

    def test_concurrent_commits_cannot_both_advance_generation(self):
        epoch, generation = lease_model.acquire(self.db, "host-a", 10, 10)
        first = local_catalog.stage(self.store, self.archive)
        (self.world / "level.dat").write_bytes(b"other contents")
        alternate = self.root / "alternate.zip"
        offline_backup.create_backup(self.world, alternate)
        second = local_catalog.stage(self.store, alternate)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda digest: local_catalog.commit(self.store, "host-a", epoch, 11, generation, digest), (first, second)))
        self.assertEqual(1, sum(result is not None for result in results))
        self.assertEqual(1, lease_model.state(self.db)[3])
        self.assertIn(lease_model.state(self.db)[4], (first, second))

    def test_verified_commit_retry_does_not_advance_generation(self):
        epoch, generation = lease_model.acquire(self.db, "host-a", 10, 10)
        digest = local_catalog.stage(self.store, self.archive)
        self.assertEqual((1,), local_catalog.commit_once(self.store, "request-1", "host-a", epoch, 11, generation, digest))
        self.assertEqual((1,), local_catalog.commit_once(self.store, "request-1", "host-a", epoch, 12, generation, digest))
        self.assertEqual((1, digest), lease_model.state(self.db)[3:])


if __name__ == "__main__":
    unittest.main()
