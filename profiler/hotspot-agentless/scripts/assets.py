#!/usr/bin/env python3
"""Build-time local OCI graph export, integrity verification and atomic installation.

No network, compiler, registry or privilege operations are performed here.
The distribution manifest is trusted operator input, not a cryptographic signature.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile

MAX_MANIFEST = 65536
MAX_FILES = 256
MAX_BYTES = 512 * 1024 * 1024
SHA256 = re.compile(r"[a-f0-9]{64}\Z")
VERSION = {
    "schema_version": 1,
    "collector_version": "2",
    "capture_abi": 2,
    "wire_schema": 2,
    "platform": "linux-amd64",
    "gadget_revision": "7097db144ccd8dab15373f27ec98d2c3d22e4588",
    "profiler_revision": "ef6cf29492c8a980f889052903fa2dd0c0765cc0",
    "patch_sha256": "e908bb2689936481f8a696dd2347457cc44b6344c1fa78ca65b37772032703eb",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def hash_file(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def owned(root, name):
    parts = name.split("/")
    require(not root.is_symlink() and all(p and p not in (".", "..") and "\\" not in p for p in parts), "invalid asset path")
    current = root
    for part in parts:
        current = current / part
        require(not current.is_symlink(), "symbolic asset path")
    if not current.exists():
        raise FileNotFoundError(current)
    require(current.is_file(), "missing asset: " + name)
    return current


def digest_name(value):
    require(value.startswith("sha256:") and SHA256.fullmatch(value[7:]), "invalid OCI digest")
    return value[7:]


def graph(store, digest):
    """Validate and return exactly the reachable blobs, including all BPF layers."""
    pending = [{"digest": digest}]
    found = {}
    total = 0
    while pending:
        descriptor = pending.pop()
        name = digest_name(descriptor["digest"])
        path = owned(store, "blobs/sha256/" + name)
        size = path.stat().st_size
        require("size" not in descriptor or descriptor["size"] == size, "OCI descriptor size mismatch")
        if name in found:
            continue
        require(hash_file(path) == name, "OCI blob checksum mismatch")
        found[name] = path
        total += size
        require(len(found) <= MAX_FILES and total <= MAX_BYTES, "OCI graph limit exceeded")
        media = descriptor.get("mediaType", "application/vnd.oci.image.index.v1+json")
        if media in ("application/vnd.oci.image.index.v1+json", "application/vnd.oci.image.manifest.v1+json"):
            require(size <= MAX_MANIFEST, "OCI descriptor JSON exceeds limit")
            data = json.loads(path.read_text())
            require(data.get("schemaVersion") == 2, "unsupported OCI schema")
            pending.extend(data.get("manifests", []))
            pending.extend(data.get("layers", []))
            if "config" in data:
                pending.append(data["config"])
    return found


def verify(bundle):
    bundle = Path(bundle).absolute()
    manifest_path = owned(bundle, "manifest.json")
    require(manifest_path.stat().st_size <= MAX_MANIFEST, "manifest exceeds limit")
    manifest = json.loads(manifest_path.read_text())
    require(all(manifest.get(k) == v for k, v in VERSION.items()), "unsupported asset version/ABI/provenance")
    files = manifest["files"]
    require(0 < len(files) <= MAX_FILES, "asset file limit exceeded")
    required = {"mw-hotspot-collector", "oci/index.json", "oci/oci-layout", "oci/blobs/sha256/" + digest_name(manifest["gadget_digest"])}
    require(required <= files.keys(), "incomplete asset manifest")
    total = 0
    for name, expected in files.items():
        require(SHA256.fullmatch(expected), "invalid asset checksum")
        path = owned(bundle, name)
        total += path.stat().st_size
        require(total <= MAX_BYTES, "asset size limit exceeded")
        require(hash_file(path) == expected, "asset checksum mismatch: " + name)
    require(os.access(bundle / "mw-hotspot-collector", os.X_OK), "collector is not executable")
    blobs = graph(bundle / "oci", manifest["gadget_digest"])
    require(all("oci/blobs/sha256/" + name in files for name in blobs), "unlisted reachable OCI blob")
    return manifest


def publish(output, populate):
    require(bool(str(output)), "explicit destination required")
    output = Path(output).absolute()
    require(bool(output.name), "explicit destination required")
    if output.exists() or output.is_symlink():
        raise FileExistsError(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".mw-assets-", dir=output.parent) as temp:
        staged = Path(temp) / "bundle"
        staged.mkdir()
        populate(staged)
        verify(staged)
        # Never replace an existing operator installation.
        if output.exists() or output.is_symlink():
            raise FileExistsError(output)
        staged.rename(output)


def pack(collector, store, image, output):
    collector, store = Path(collector).absolute(), Path(store).absolute()
    require(collector.is_file() and not collector.is_symlink() and os.access(collector, os.X_OK), "invalid collector executable")
    require(image.startswith("ghcr.io/inspektor-gadget/gadget/mw-hotspot-openat@"), "unexpected gadget image")
    digest = image.split("@", 1)[1]
    blobs = graph(store, digest)
    index = json.loads(owned(store, "index.json").read_text())
    descriptor = next(d for d in index["manifests"] if d["digest"] == digest)

    def populate(staged):
        shutil.copy2(collector, staged / "mw-hotspot-collector")
        oci = staged / "oci"
        oci.joinpath("blobs/sha256").mkdir(parents=True)
        shutil.copy2(owned(store, "oci-layout"), oci / "oci-layout")
        (oci / "index.json").write_text(json.dumps({"schemaVersion": 2, "manifests": [descriptor]}, sort_keys=True) + "\n")
        for name, path in blobs.items():
            shutil.copy2(path, oci / "blobs/sha256" / name)
        files = {str(p.relative_to(staged)): hash_file(p) for p in sorted(staged.rglob("*")) if p.is_file()}
        (staged / "manifest.json").write_text(json.dumps({**VERSION, "gadget_digest": digest, "files": files}, indent=2, sort_keys=True) + "\n")

    publish(output, populate)


def install(bundle, output):
    bundle = Path(bundle).absolute()
    manifest = verify(bundle)

    def populate(staged):
        for name in [*manifest["files"], "manifest.json"]:
            destination = staged / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(owned(bundle, name), destination)

    publish(output, populate)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    build = commands.add_parser("pack")
    for option in ("collector", "store", "image", "output"):
        build.add_argument("--" + option, required=True)
    check = commands.add_parser("verify")
    check.add_argument("--bundle", required=True)
    copy = commands.add_parser("install")
    copy.add_argument("--bundle", required=True)
    copy.add_argument("--output", required=True)
    args = vars(parser.parse_args())
    command = args.pop("command")
    {"pack": pack, "verify": verify, "install": install}[command](**args)


if __name__ == "__main__":
    main()
