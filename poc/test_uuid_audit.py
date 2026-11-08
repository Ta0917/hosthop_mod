import json
from pathlib import Path
import tempfile
import unittest
import uuid

import uuid_audit


class UuidAuditTest(unittest.TestCase):
    def test_known_offline_uuid(self):
        self.assertEqual("b50ad385-829d-3141-a216-7e7d7539ba7f", uuid_audit.offline_uuid("Notch"))

    def test_duplicate_group_and_files_are_read_only(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as temp:
            root = Path(temp)
            data = root / "world" / "players" / "data"
            data.mkdir(parents=True)
            offline = uuid_audit.offline_uuid("Notch")
            other = str(uuid.uuid4())
            cache = root / "usercache.json"
            cache.write_text(json.dumps([{"name": "Notch", "uuid": offline}, {"name": "Notch", "uuid": other}]), encoding="utf-8")
            (data / (offline + ".dat")).write_bytes(b"offline data")
            (data / (other + ".dat")).write_bytes(b"other data")
            before = {p.name: p.read_bytes() for p in data.iterdir()}
            result = uuid_audit.audit(cache, root / "world")
            self.assertEqual(2, result["cache_entries"])
            self.assertEqual(1, result["duplicate_name_groups"])
            self.assertEqual(1, result["cache_entries_matching_offline_uuid"])
            self.assertEqual(1, result["duplicate_name_groups_with_offline_uuid_and_multiple_data_files"])
            self.assertEqual(before, {p.name: p.read_bytes() for p in data.iterdir()})

    def test_invalid_cache_is_not_silently_ignored(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as temp:
            root = Path(temp)
            (root / "players" / "data").mkdir(parents=True)
            cache = root / "cache.json"
            cache.write_text('[{"name":"test","uuid":"invalid"}]', encoding="utf-8")
            with self.assertRaises(ValueError):
                uuid_audit.audit(cache, root)


if __name__ == "__main__":
    unittest.main()
