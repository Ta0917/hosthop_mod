"""Local commit-protocol experiment. Not a cloud service or production lock.

Assumes a trusted local object directory and an externally supplied test clock.
It does not establish snapshot consistency or protect against a hostile host.
"""
import hashlib
import os
from pathlib import Path
import re
import tempfile

import lease_model
import offline_backup


def initialize(root):
    root = Path(root)
    root.mkdir(parents=True, exist_ok=True)
    (root / "objects").mkdir(exist_ok=True)
    lease_model.initialize(root / "catalog.sqlite")


def _hash(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def _object_path(root, digest):
    if not isinstance(digest, str) or re.fullmatch(r"[0-9a-f]{64}", digest) is None:
        raise ValueError("invalid object digest")
    return Path(root) / "objects" / (digest + ".zip")


def _verify_object(root, digest):
    obj = _object_path(root, digest)
    if obj.is_symlink() or not obj.is_file() or _hash(obj) != digest:
        raise ValueError("object is missing or corrupted")
    offline_backup.verify_backup(obj)
    return obj


def stage(root, archive):
    """Copy and verify first; publish without replacing an existing object."""
    directory = Path(root) / "objects"
    fd, temp_name = tempfile.mkstemp(prefix="incoming-", suffix=".tmp", dir=directory)
    temporary = Path(temp_name)
    try:
        with os.fdopen(fd, "wb") as target, Path(archive).open("rb") as source:
            while chunk := source.read(1024 * 1024):
                target.write(chunk)
            target.flush()
            os.fsync(target.fileno())
        digest = _hash(temporary)
        offline_backup.verify_backup(temporary)
        destination = _object_path(root, digest)
        try:
            # A hard link publishes the complete staged object without overwriting.
            os.link(temporary, destination)
        except FileExistsError:
            _verify_object(root, digest)
        except PermissionError:
            if os.name != "nt":
                raise
            # Windows rename fails if the destination exists; POSIX rename may
            # overwrite, so this fallback must never be used on POSIX.
            try:
                os.rename(temporary, destination)
            except FileExistsError:
                _verify_object(root, digest)
        return digest
    finally:
        temporary.unlink(missing_ok=True)


def commit(root, owner, epoch, now, expected_generation, digest):
    _verify_object(root, digest)
    return lease_model.commit(Path(root) / "catalog.sqlite", owner, epoch, now, expected_generation, digest)


def commit_once(root, operation_id, owner, epoch, now, expected_generation, digest):
    _verify_object(root, digest)
    return lease_model.commit_once(Path(root) / "catalog.sqlite", operation_id, owner, epoch, now, expected_generation, digest)


def restore_latest(root, destination):
    epoch, owner, expires, generation, digest = lease_model.state(Path(root) / "catalog.sqlite")
    if generation == 0 or digest is None:
        raise ValueError("no committed generation")
    obj = _verify_object(root, digest)
    return generation, offline_backup.restore_backup(obj, destination)
