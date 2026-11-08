"""Read-only comparison of duplicated player records; no automatic merging."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import uuid

from uuid_audit import offline_uuid
import snapshot_manifest


def read_progress(world, player_id):
    import nbtlib  # Optional, needed only for the read-only NBT comparison.

    players = Path(world) / "players"
    nbt_path = players / "data" / (player_id + ".dat")
    stats_path = players / "stats" / (player_id + ".json")
    advancements_path = players / "advancements" / (player_id + ".json")
    if not all(path.is_file() and not path.is_symlink() and not path.is_junction() for path in (nbt_path, stats_path, advancements_path)):
        return None
    nbt = nbtlib.load(nbt_path, gzipped=True)
    internal = nbt.get("UUID")
    if internal is None or len(internal) != 4:
        return None
    encoded = b"".join((int(part) & 0xffffffff).to_bytes(4, "big") for part in internal)
    if str(uuid.UUID(bytes=encoded)) != player_id:
        return None
    stats = json.loads(stats_path.read_text(encoding="utf-8")).get("stats", {})
    advances = json.loads(advancements_path.read_text(encoding="utf-8"))
    if not isinstance(stats, dict) or not isinstance(advances, dict):
        raise ValueError("invalid player statistics or advancements")
    custom = stats.get("minecraft:custom", {})
    play_ticks = custom.get("minecraft:play_time") if isinstance(custom, dict) else None
    if type(play_ticks) is not int or play_ticks < 0:
        return None
    return {
        "internal_uuid_matches_filename": True,
        "play_ticks": play_ticks,
        "completed_advancements": sum(isinstance(value, dict) and value.get("done") is True for value in advances.values()),
        "stat_entries": sum(len(value) for value in stats.values() if isinstance(value, dict)),
        "inventory_entries": len(nbt.get("Inventory", [])),
        "ender_chest_entries": len(nbt.get("EnderItems", [])),
        "xp_level": int(nbt.get("XpLevel", 0)),
    }


def clearly_ahead(candidate, other):
    """Conservative heuristic; XP and current inventory can decrease during play."""
    if candidate is None or other is None:
        return False
    a, b = candidate["play_ticks"], other["play_ticks"]
    return (a >= b * 2 and a - b >= 6000
            and candidate["completed_advancements"] - other["completed_advancements"] >= 5
            and candidate["stat_entries"] - other["stat_entries"] >= 20)


def compare(usercache, world):
    records = json.loads(Path(usercache).read_text(encoding="utf-8"))
    if not isinstance(records, list):
        raise ValueError("user cache must be a list")
    groups = collections.defaultdict(dict)
    for item in records:
        if not isinstance(item, dict) or not isinstance(item.get("name"), str) or not item["name"] or not isinstance(item.get("uuid"), str):
            raise ValueError("invalid user cache entry")
        player_id = str(uuid.UUID(item["uuid"]))
        groups[item["name"].casefold()][player_id] = item["name"]
    result = []
    for identities in groups.values():
        if len(identities) < 2:
            continue
        profiles = []
        for player_id, name in sorted(identities.items()):
            profiles.append({"name": name, "uuid": player_id,
                             "matches_offline_uuid": player_id == offline_uuid(name),
                             "progress": read_progress(world, player_id)})
        winners = [profile for profile in profiles if all(
            clearly_ahead(profile["progress"], other["progress"]) for other in profiles if other is not profile)]
        result.append({"profiles": profiles, "selected_uuid": winners[0]["uuid"] if len(winners) == 1 else None})
    world_manifest = snapshot_manifest.manifest(world)
    canonical_manifest = json.dumps(world_manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return {
        "format": 1,
        "method": "play time, completed advancements and statistic entries; no data edited",
        "scope": "This selection applies only to the exact audited copies; re-evaluate later world data.",
        "usercache_sha256": hashlib.sha256(Path(usercache).read_bytes()).hexdigest(),
        "world_manifest_sha256": hashlib.sha256(canonical_manifest).hexdigest(),
        "groups": result,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("usercache", help="path to a copied usercache.json")
    parser.add_argument("world_copy", help="path to a copied world directory")
    parser.add_argument("output", help="new report file outside the world directory")
    args = parser.parse_args()
    world = Path(args.world_copy).resolve()
    output = Path(args.output).resolve()
    if output == world or world in output.parents:
        parser.error("report must be outside the world directory")
    report = compare(args.usercache, args.world_copy)
    with output.open("x", encoding="utf-8") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(f"Compared {len(report['groups'])} duplicate name groups; selected {sum(group['selected_uuid'] is not None for group in report['groups'])} clear candidates.")
