import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest import mock
import uuid

import player_progress
import uuid_audit


class PlayerProgressTest(unittest.TestCase):
    def test_clear_lead_requires_all_indicators(self):
        old = {"play_ticks": 10000, "completed_advancements": 2, "stat_entries": 10}
        new = {"play_ticks": 25000, "completed_advancements": 20, "stat_entries": 100}
        self.assertTrue(player_progress.clearly_ahead(new, old))
        self.assertFalse(player_progress.clearly_ahead(old, new))
        self.assertFalse(player_progress.clearly_ahead(None, old))
        self.assertFalse(player_progress.clearly_ahead(dict(new, completed_advancements=4), old))
        self.assertFalse(player_progress.clearly_ahead(dict(new, stat_entries=20), old))
        self.assertFalse(player_progress.clearly_ahead(dict(new, play_ticks=15000), old))

    def test_selects_only_clear_complete_group_without_modifying_data(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as temp:
            root = Path(temp)
            world = root / "world"
            world.mkdir()
            sentinel = world / "level.dat"
            sentinel.write_bytes(b"world contents")
            original = uuid_audit.offline_uuid("Notch")
            chosen = str(uuid.uuid4())
            cache = root / "cache.json"
            cache.write_text(json.dumps([{"name": "Notch", "uuid": original}, {"name": "Notch", "uuid": chosen}]), encoding="utf-8")
            values = {
                original: {"play_ticks": 10000, "completed_advancements": 2, "stat_entries": 10},
                chosen: {"play_ticks": 25000, "completed_advancements": 20, "stat_entries": 100},
            }
            with mock.patch.object(player_progress, "read_progress", side_effect=lambda _, player_id: values[player_id]):
                result = player_progress.compare(cache, world)
            self.assertEqual(chosen, result["groups"][0]["selected_uuid"])
            self.assertEqual(b"world contents", sentinel.read_bytes())
            self.assertEqual(64, len(result["world_manifest_sha256"]))
            values[original] = None
            with mock.patch.object(player_progress, "read_progress", side_effect=lambda _, player_id: values[player_id]):
                unresolved = player_progress.compare(cache, world)
            self.assertIsNone(unresolved["groups"][0]["selected_uuid"])

    def test_missing_or_mismatched_internal_uuid_is_not_selected(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as temp:
            root = Path(temp)
            player_id = str(uuid.uuid4())
            for folder in ("data", "stats", "advancements"):
                (root / "players" / folder).mkdir(parents=True)
            (root / "players" / "data" / (player_id + ".dat")).write_bytes(b"placeholder")
            (root / "players" / "stats" / (player_id + ".json")).write_text('{"stats":{"minecraft:custom":{"minecraft:play_time":1}}}', encoding="utf-8")
            (root / "players" / "advancements" / (player_id + ".json")).write_text('{}', encoding="utf-8")
            fake = types.SimpleNamespace(load=lambda *args, **kwargs: {"UUID": [0, 0, 0, 0]})
            with mock.patch.dict("sys.modules", {"nbtlib": fake}):
                self.assertIsNone(player_progress.read_progress(root, player_id))
                fake.load = lambda *args, **kwargs: {}
                self.assertIsNone(player_progress.read_progress(root, player_id))


if __name__ == "__main__":
    unittest.main()
