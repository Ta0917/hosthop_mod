"""Hash an already stopped, quiescent world copy. Does not make a snapshot."""
import hashlib
import json
from pathlib import Path


def _files(root):
    root = Path(root)
    if root.is_symlink() or root.is_junction() or not root.is_dir():
        raise ValueError("root must be a non-symlink directory")
    result = []
    for path in root.rglob("*"):
        if path.is_symlink() or path.is_junction():
            raise ValueError("links are not supported")
        if path.is_file():
            result.append(path)
        elif not path.is_dir():
            raise ValueError("special files are not supported")
    return sorted(result)


def manifest(root):
    root = Path(root)
    files = _files(root)
    entries = {}
    for path in files:
        before = path.stat()
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            while chunk := stream.read(1024 * 1024):
                digest.update(chunk)
        after = path.stat()
        if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
            raise RuntimeError(f"file changed during hashing: {path.relative_to(root)}")
        entries[path.relative_to(root).as_posix()] = {"size": after.st_size, "sha256": digest.hexdigest()}
    if files != _files(root):
        raise RuntimeError("file set changed during hashing")
    return {"format": 1, "files": entries}


def verify(root, expected):
    if expected.get("format") != 1 or not isinstance(expected.get("files"), dict):
        raise ValueError("unsupported manifest")
    return manifest(root) == expected


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("world_copy")
    parser.add_argument("manifest_file", help="output must be outside the world copy")
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    root = Path(args.world_copy).resolve()
    output = Path(args.manifest_file).resolve()
    if output == root or root in output.parents:
        parser.error("manifest must be outside the world copy")
    if args.verify:
        with output.open(encoding="utf-8") as stream:
            ok = verify(root, json.load(stream))
        print("OK" if ok else "MISMATCH")
        raise SystemExit(0 if ok else 1)
    result = manifest(root)
    with output.open("x", encoding="utf-8") as stream:
        json.dump(result, stream, ensure_ascii=False, sort_keys=True, indent=2)
        stream.write("\n")
