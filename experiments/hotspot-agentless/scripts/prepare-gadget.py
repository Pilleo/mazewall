#!/usr/bin/env python3
"""Apply the scoped Gadget patch to verified, generated build sources."""
import hashlib
import argparse
import json
from pathlib import Path
import shutil
import subprocess


def prepare():
    parser = argparse.ArgumentParser()
    parser.add_argument("--drop-first-definition", action="store_true", help="test-only fault-injected build")
    options = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    lock = json.loads((root / "dependencies.lock.json").read_text())
    module = lock["gadget"]
    subprocess.run(["go", "mod", "verify"], cwd=root, check=True)
    info = json.loads(subprocess.check_output(
        ["go", "mod", "download", "-json", module["module"] + "@" + module["version"]],
        cwd=root, text=True))
    if info.get("Sum") != module["sum"]:
        raise RuntimeError("Gadget module checksum differs from the lock")
    patch = root / "patches/gadget-structured.patch"
    health_patch = root / "patches/gadget-health.patch"
    additions = sorted((root / "patches/gadget-additions").rglob("*.go.txt"))
    profiler = lock["profiler"]
    profiler_info = json.loads(subprocess.check_output(
        ["go", "mod", "download", "-json", profiler["replacement"] + "@" + profiler["version"]],
        cwd=root, text=True))
    if profiler_info.get("Sum") != profiler["sum"]:
        raise RuntimeError("Profiler module checksum differs from the lock")
    profiler_patch = root / "patches/profiler-health.patch"
    quality_patch = root / "patches/profiler-quality.patch"
    identity_patch = root / "patches/profiler-identity.patch"
    profiler_additions = sorted((root / "patches/profiler-additions").rglob("*.go.txt"))
    fault = root / "patches/faults/drop-first-definition.patch" if options.drop_first_definition else None
    digest = hashlib.sha256()
    for path in [patch, health_patch, *additions, profiler_patch, quality_patch, identity_patch, *profiler_additions, *([fault] if fault else [])]:
        digest.update(str(path.relative_to(root)).encode())
        digest.update(b"\0")
        digest.update(path.read_bytes())
    patch_id = digest.hexdigest()
    generated = root / "build/deps" / ("gadget-" + patch_id)
    marker = generated / ".mw-prepared.json"
    generated_profiler = root / "build/deps" / ("profiler-" + patch_id)
    manifest = {"base_revision": module["revision"], "base_sum": info["Sum"],
                "patch_sha256": patch_id, "submitted_upstream": False,
                "fault_injection": "drop_first_definition" if fault else None,
                "profiler_base_revision": profiler["revision"], "profiler_base_sum": profiler_info["Sum"]}
    profiler_marker = generated_profiler / ".mw-prepared.json"
    if not profiler_marker.exists():
        if generated_profiler.exists():
            raise RuntimeError("Incomplete generated profiler dependency: " + str(generated_profiler))
        shutil.copytree(profiler_info["Dir"], generated_profiler)
        generated_profiler.chmod(0o755)
        for path in generated_profiler.rglob("*"):
            path.chmod(0o755 if path.is_dir() else 0o644)
        subprocess.run(["patch", "--batch", "--forward", "--fuzz=0", "-p1", "-i", str(profiler_patch)], cwd=generated_profiler, check=True)
        subprocess.run(["patch", "--batch", "--forward", "--fuzz=0", "-p1", "-i", str(quality_patch)], cwd=generated_profiler, check=True)
        subprocess.run(["patch", "--batch", "--forward", "--fuzz=0", "-p1", "-i", str(identity_patch)], cwd=generated_profiler, check=True)
        for path in profiler_additions:
            dest = generated_profiler / path.relative_to(root / "patches/profiler-additions").with_suffix("")
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, dest)
        profiler_marker.write_text(json.dumps(manifest, indent=2) + "\n")
    elif json.loads(profiler_marker.read_text()) != manifest:
        raise RuntimeError("Generated profiler provenance mismatch")
    if not marker.exists():
        if generated.exists():
            raise RuntimeError("Incomplete generated dependency: " + str(generated))
        shutil.copytree(info["Dir"], generated)
        generated.chmod(0o755)
        for path in generated.rglob("*"):
            path.chmod(0o755 if path.is_dir() else 0o644)
        subprocess.run(["patch", "--batch", "--forward", "--fuzz=0", "-p1", "-i", str(patch)],
                       cwd=generated, check=True)
        subprocess.run(["patch", "--batch", "--forward", "--fuzz=0", "-p1", "-i", str(health_patch)], cwd=generated, check=True)
        for path in additions:
            dest = generated / path.relative_to(root / "patches/gadget-additions").with_suffix("")
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, dest)
        if fault:
            subprocess.run(["patch", "--batch", "--forward", "--fuzz=0", "-p1", "-i", str(fault)], cwd=generated, check=True)
        marker.write_text(json.dumps(manifest, indent=2) + "\n")
    elif json.loads(marker.read_text()) != manifest:
        raise RuntimeError("Generated dependency provenance mismatch")
    name = "recovery-test" if fault else "experiment"
    modfile = root / "build" / (name + ".go.mod")
    shutil.copyfile(root / "go.mod", modfile)
    shutil.copyfile(root / "go.sum", modfile.with_suffix(".sum"))
    relative = "./" + str(generated.relative_to(root))
    subprocess.run(["go", "mod", "edit", "-modfile=" + str(modfile.relative_to(root)),
                    "-replace=" + module["module"] + "=" + relative], cwd=root, check=True)
    subprocess.run(["go", "mod", "edit", "-modfile=" + str(modfile.relative_to(root)),
                    "-replace=" + profiler["module"] + "=./" + str(generated_profiler.relative_to(root))], cwd=root, check=True)
    manifest_file = "recovery-test-manifest.json" if fault else "patch-manifest.json"
    (root / "build" / manifest_file).write_text(json.dumps(manifest, indent=2) + "\n")
    print("Prepared Gadget patch " + patch_id)


if __name__ == "__main__":
    prepare()
