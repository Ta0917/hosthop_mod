"""Offline backup experiment for an already stopped copy; not a live-world snapshot."""
import hashlib
import json
import re
import zipfile
from pathlib import Path, PurePosixPath

import snapshot_manifest

MANIFEST = "hosthop-manifest.json"
MAX_MANIFEST = 16 * 1024 * 1024
MAX_TOTAL = 64 * 1024 * 1024 * 1024
_RESERVED = {"CON", "PRN", "AUX", "NUL", *(f"COM{i}" for i in range(1, 10)), *(f"LPT{i}" for i in range(1, 10))}


def _valid_path(name):
    if not isinstance(name, str) or not name or any(char in name for char in '\\:*?"<>|') or any(ord(char) < 32 for char in name):
        return False
    path = PurePosixPath(name)
    if path.is_absolute() or not path.parts or path.as_posix() != name:
        return False
    return all(part not in ("", ".", "..") and part == part.rstrip(" .") and part.split(".")[0].upper() not in _RESERVED for part in path.parts)


def _load_manifest(archive):
    infos = archive.infolist()
    names = [entry.filename for entry in infos]
    if len(names) != len(set(names)) or names.count(MANIFEST) != 1:
        raise ValueError("duplicate or missing archive entries")
    info = archive.getinfo(MANIFEST)
    if info.file_size > MAX_MANIFEST or info.is_dir():
        raise ValueError("invalid manifest size")
    manifest = json.loads(archive.read(info))
    entries = manifest.get("files") if isinstance(manifest, dict) else None
    if not isinstance(manifest, dict) or manifest.get("format") != 1 or not isinstance(entries, dict):
        raise ValueError("unsupported manifest")
    folded = set()
    total = 0
    for name, item in entries.items():
        if not _valid_path(name) or name.casefold() in folded:
            raise ValueError("invalid or conflicting path")
        folded.add(name.casefold())
        if not isinstance(item, dict) or type(item.get("size")) is not int or item["size"] < 0 or not isinstance(item.get("sha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", item["sha256"]):
            raise ValueError("invalid file metadata")
        total += item["size"]
        if total > MAX_TOTAL:
            raise ValueError("backup exceeds experiment size limit")
    for name in entries:
        parts = PurePosixPath(name).parts
        if any("/".join(parts[:i]).casefold() in folded for i in range(1, len(parts))):
            raise ValueError("file path conflicts with a directory")
    if set(names) != {MANIFEST, *("world/" + name for name in entries)}:
        raise ValueError("unexpected or missing archive entries")
    for name, item in entries.items():
        info = archive.getinfo("world/" + name)
        if info.is_dir() or info.file_size != item["size"] or (info.external_attr >> 16) & 0o170000 == 0o120000:
            raise ValueError("invalid archive entry")
    return entries


def _copy_verified(source, target, item):
    digest = hashlib.sha256()
    size = 0
    while chunk := source.read(1024 * 1024):
        size += len(chunk)
        if size > item["size"]:
            raise ValueError("file exceeds recorded size")
        digest.update(chunk)
        if target is not None:
            target.write(chunk)
    if size != item["size"] or digest.hexdigest() != item["sha256"]:
        raise ValueError("file integrity check failed")


def verify_backup(path):
    with zipfile.ZipFile(path, "r") as archive:
        entries = _load_manifest(archive)
        for name, item in entries.items():
            with archive.open("world/" + name) as source:
                _copy_verified(source, None, item)
        return len(entries)


def create_backup(world_copy, destination):
    root = Path(world_copy)
    resolved_root = root.resolve()
    destination = Path(destination).resolve()
    if destination == resolved_root or resolved_root in destination.parents:
        raise ValueError("backup must be outside the world copy")
    data = snapshot_manifest.manifest(root)
    folded = set()
    for name in data["files"]:
        if not _valid_path(name) or name.casefold() in folded:
            raise ValueError("world contains a non-portable path")
        folded.add(name.casefold())
    # Exclusive creation guarantees that a previous backup is never overwritten.
    with destination.open("xb") as output:
        # On failure the incomplete file is retained, never promoted as a valid backup.
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=1, allowZip64=True) as archive:
            archive.writestr(MANIFEST, json.dumps(data, ensure_ascii=False, sort_keys=True).encode("utf-8"))
            for name, item in data["files"].items():
                with (root / name).open("rb") as source, archive.open("world/" + name, "w", force_zip64=True) as target:
                    _copy_verified(source, target, item)
        output.flush()
    return verify_backup(destination)


def restore_backup(backup, destination):
    # Verify completely before creating anything at the restoration destination.
    verify_backup(backup)
    target_root = Path(destination)
    target_root.mkdir(parents=False, exist_ok=False)
    with zipfile.ZipFile(backup, "r") as archive:
        entries = _load_manifest(archive)
        for name, item in entries.items():
            path = target_root.joinpath(*PurePosixPath(name).parts)
            path.parent.mkdir(parents=True, exist_ok=True)
            with archive.open("world/" + name) as source, path.open("xb") as target:
                _copy_verified(source, target, item)
    return len(entries)
