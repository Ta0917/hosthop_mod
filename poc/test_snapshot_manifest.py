import pathlib
import tempfile
import unittest

import snapshot_manifest as snapshot


class SnapshotManifestTest(unittest.TestCase):
    def test_detects_changes_and_unexpected_files(self):
        with tempfile.TemporaryDirectory(dir=pathlib.Path(__file__).parent) as temp:
            root = pathlib.Path(temp)
            (root / "dimensions" / "mod").mkdir(parents=True)
            region = root / "dimensions" / "mod" / "region.dat"
            region.write_bytes(b"abc")
            expected = snapshot.manifest(root)
            self.assertTrue(snapshot.verify(root, expected))
            region.write_bytes(b"abd")
            self.assertFalse(snapshot.verify(root, expected))
            region.write_bytes(b"abc")
            (root / "extra").write_bytes(b"extra")
            self.assertFalse(snapshot.verify(root, expected))
            (root / "extra").unlink()
            region.unlink()
            self.assertFalse(snapshot.verify(root, expected))


if __name__ == "__main__":
    unittest.main()
