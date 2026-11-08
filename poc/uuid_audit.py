"""Read-only, aggregate UUID audit; never edits or merges player data."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import uuid


def offline_uuid(name):
    # Minecraft's nameUUIDFromBytes uses MD5 for offline identity, not security.
    digest = hashlib.md5(("OfflinePlayer:" + name).encode("utf-8"), usedforsecurity=False).digest()
    return str(uuid.UUID(bytes=digest, version=3))


def audit(usercache, world_copy):
    records = json.loads(Path(usercache).read_text(encoding="utf-8"))
    if not isinstance(records, list):
        raise ValueError("user cache must be a list")
    groups = collections.defaultdict(list)
    offline_entries = 0
    for item in records:
        if not isinstance(item, dict) or not isinstance(item.get("name"), str) or not item["name"] or not isinstance(item.get("uuid"), str):
            raise ValueError("invalid user cache entry")
        player_id = str(uuid.UUID(item["uuid"]))
        expected_offline = offline_uuid(item["name"])
        offline_entries += player_id == expected_offline
        groups[item["name"].casefold()].append((player_id, expected_offline))
    player_dir = Path(world_copy) / "players" / "data"
    if not player_dir.is_dir():
        raise ValueError("world player data directory is missing")
    player_ids = set()
    unrecognized_files = 0
    for path in player_dir.glob("*.dat"):
        if not path.is_file() or path.is_symlink() or path.is_junction():
            raise ValueError("unsupported player data entry")
        try:
            player_ids.add(str(uuid.UUID(path.stem)))
        except ValueError:
            unrecognized_files += 1
    duplicate_groups = 0
    offline_duplicate_groups = 0
    for entries in groups.values():
        ids = {entry[0] for entry in entries}
        offline_ids = {entry[1] for entry in entries}
        if len(ids) >= 2:
            duplicate_groups += 1
            if ids & offline_ids & player_ids and len(ids & player_ids) >= 2:
                offline_duplicate_groups += 1
    return {
        "cache_entries": len(records),
        "duplicate_name_groups": duplicate_groups,
        "cache_entries_matching_offline_uuid": offline_entries,
        "player_data_uuids": len(player_ids),
        "unrecognized_dat_files": unrecognized_files,
        "duplicate_name_groups_with_offline_uuid_and_multiple_data_files": offline_duplicate_groups,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("usercache", help="path to a copied usercache.json")
    parser.add_argument("world_copy", help="path to a copied world directory")
    args = parser.parse_args()
    print(json.dumps(audit(args.usercache, args.world_copy), indent=2, ensure_ascii=False))
