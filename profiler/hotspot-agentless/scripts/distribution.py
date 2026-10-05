#!/usr/bin/env python3
"""Explicit local release packaging; never part of collector/library startup."""
import argparse
import gzip
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

import assets

EPOCH = 1790985600


def write_checksums(root):
    root = Path(root)
    entries = []
    for path in sorted(root.rglob("*")):
        assets.require(not path.is_symlink(), "symbolic distribution path")
        if path.is_file() and path != root / "SHA256SUMS":
            name = path.relative_to(root).as_posix()
            assets.require("\n" not in name and "\r" not in name, "invalid distribution filename")
            entries.append(assets.hash_file(path) + "  " + name + "\n")
    (root / "SHA256SUMS").write_text("".join(entries))


def check_checksums(root):
    root = Path(root)
    listed = set()
    for line in assets.owned(root, "SHA256SUMS").read_text().splitlines():
        digest, name = line.split("  ", 1)
        assets.require(assets.SHA256.fullmatch(digest) and name not in listed, "invalid distribution checksum")
        assets.require(assets.hash_file(assets.owned(root, name)) == digest, "distribution checksum mismatch: " + name)
        listed.add(name)
    actual = set()
    for path in root.rglob("*"):
        assets.require(not path.is_symlink(), "symbolic distribution path")
        if path.is_file() and path != root / "SHA256SUMS":
            actual.add(path.relative_to(root).as_posix())
    assets.require(actual == listed, "unlisted distribution files")


def archive(root, output):
    """Sorted fixed-metadata gzip/tar, identical bytes for identical input trees."""
    with Path(output).open("xb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=EPOCH) as compressed:
            with tarfile.open(fileobj=compressed, mode="w", format=tarfile.PAX_FORMAT) as target:
                for path in sorted(Path(root).rglob("*")):
                    assets.require(not path.is_symlink(), "symbolic source/distribution path")
                    if not path.is_file():
                        continue
                    info = target.gettarinfo(str(path), path.relative_to(root).as_posix())
                    info.uid = info.gid = 0
                    info.uname = info.gname = ""
                    info.mtime = EPOCH
                    info.mode = 0o755 if path.stat().st_mode & 0o111 else 0o644
                    with path.open("rb") as stream:
                        target.addfile(info, stream)


def module_graph(engine):
    data = subprocess.check_output(
        ["go", "list", "-modfile", "build/experiment.go.mod", "-m", "-json", "all"], cwd=engine, text=True,
    )
    decoder = json.JSONDecoder()
    modules = {}
    while data.strip():
        module, end = decoder.raw_decode(data.lstrip())
        data = data.lstrip()[end:]
        modules[module["Path"]] = module
    return modules


def release(bundle, output, library_version):
    bundle, output = Path(bundle).absolute(), Path(output).absolute()
    engine = Path(__file__).resolve().parent.parent
    manifest = assets.verify(bundle)
    assets.require(not output.exists(), "distribution destination exists")
    build_info = subprocess.check_output(["go", "version", "-m", str(bundle / "mw-hotspot-collector")], text=True)
    graph = module_graph(engine)
    selected = [line.split()[1] for line in build_info.splitlines() if line.strip().startswith("dep\t")]
    assets.require(bool(selected), "collector has no embedded module provenance")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".mw-release-", dir=output.parent) as temp:
        root = Path(temp) / "release"
        root.mkdir()
        assets.install(bundle, root / "assets")
        notices = []
        for name in selected:
            module = graph[name]
            source = Path(module.get("Replace", module)["Dir"])
            destination = root / "licenses" / name
            files = sorted(p for p in source.rglob("*") if p.is_file() and
                           p.name.lower().startswith(("license", "copying", "notice", "copyright")))
            assets.require(bool(files), "missing selected-module license: " + name)
            for path in files:
                target = destination / path.relative_to(source)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, target)
            replacement = module.get("Replace", module)
            notices.append({"module": name, "version": module.get("Version"),
                            "replacement_module": replacement["Path"], "replacement_version": replacement.get("Version"),
                            "sum": replacement.get("Sum"), "notices": [p.relative_to(source).as_posix() for p in files]})
        goroot = Path(subprocess.check_output(["go", "env", "GOROOT"], text=True).strip())
        (root / "licenses/go").mkdir()
        go_license = goroot / "LICENSE"
        if not go_license.is_file():
            # Debian puts the complete Go copyright/license inventory in its doc package.
            go_license = Path("/usr/share/doc") / goroot.name.replace("go-", "golang-") / "copyright"
        assets.require(go_license.is_file(), "missing Go toolchain copyright/license inventory")
        shutil.copyfile(go_license, root / "licenses/go/LICENSE")
        gadget = Path(graph["github.com/inspektor-gadget/inspektor-gadget"]["Replace"]["Dir"])
        (root / "licenses/mazewall").mkdir()
        # The project's README already declares Apache-2.0; use the verbatim standard text.
        shutil.copyfile(gadget / "LICENSE", root / "licenses/mazewall/LICENSE")
        with tempfile.TemporaryDirectory(prefix=".mw-source-", dir=output.parent) as source_temp:
            source_root = Path(source_temp)
            shutil.copytree(engine, source_root / "mazewall-hotspot", ignore=shutil.ignore_patterns("build", "__pycache__"))
            for name, directory in (("inspektor-gadget", gadget), ("opentelemetry-ebpf-profiler",
                    Path(graph["go.opentelemetry.io/ebpf-profiler"]["Replace"]["Dir"]))):
                shutil.copytree(directory, source_root / name)
            archive(source_root, root / "native-source.tar.gz")
        (root / "collector-build-info.txt").write_text(build_info)
        shutil.copyfile(engine / "dependencies.lock.json", root / "dependencies.lock.json")
        shutil.copyfile(engine.parent / "HOTSPOT_AGENTLESS.md", root / "HOTSPOT_AGENTLESS.md")
        (root / "provenance.json").write_text(json.dumps({
            "schema_version": 1, "library": "io.mazewall:profiler:" + library_version,
            "collector_version": manifest["collector_version"], "capture_abi": manifest["capture_abi"],
            "wire_schema": manifest["wire_schema"], "asset_manifest_sha256": assets.hash_file(bundle / "manifest.json"),
            "source_date_epoch": EPOCH, "modules": notices,
        }, indent=2, sort_keys=True) + "\n")
        write_checksums(root)
        check_checksums(root)
        archive(root, output)
    Path(str(output) + ".sha256").write_text(assets.hash_file(output) + "  " + output.name + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--library-version", required=True)
    release(**vars(parser.parse_args()))


if __name__ == "__main__":
    main()
