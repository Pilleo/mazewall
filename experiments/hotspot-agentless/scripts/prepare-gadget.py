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
    parser.add_argument("--identity-observations", action="store_true", help="test-only raw identity diagnostics")
    options = parser.parse_args()
    if options.identity_observations and options.drop_first_definition:
        parser.error("identity diagnostics and definition-drop fault are separate builds")
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
    gadget_epoch = root / "patches/gadget-epoch.patch"
    gadget_shutdown = root / "patches/gadget-shutdown.patch"
    gadget_event_loss = root / "patches/gadget-event-loss.patch"
    gadget_finalization = root / "patches/gadget-finalization.patch"
    gadget_definition_drain = root / "patches/gadget-definition-drain.patch"
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
    epoch_capture = root / "patches/profiler-epoch-capture.patch"
    epoch_memory = root / "patches/profiler-epoch-memory.patch"
    epoch_handler = root / "patches/profiler-epoch-handler.patch"
    profiler_shutdown = root / "patches/profiler-shutdown.patch"
    profiler_definition_drain = root / "patches/profiler-definition-drain.patch"
    profiler_metadata_retirement = root / "patches/profiler-metadata-retirement.patch"
    epoch_rebuild = root / "scripts/rebuild-profiler-ebpf.sh"
    profiler_additions = sorted((root / "patches/profiler-additions").rglob("*.go.txt"))
    fault = root / "patches/faults/drop-first-definition.patch" if options.drop_first_definition else None
    diagnostic = root / "patches/diagnostics/identity-observations.patch" if options.identity_observations else None
    diagnostic_additions = sorted((root / "patches/diagnostic-additions").rglob("*.go.txt")) if diagnostic else []
    digest = hashlib.sha256()
    for path in [patch, health_patch, gadget_epoch, gadget_shutdown, gadget_event_loss, gadget_finalization, gadget_definition_drain, *additions, profiler_patch, quality_patch, identity_patch, epoch_capture, epoch_memory, epoch_handler, profiler_shutdown, profiler_definition_drain, profiler_metadata_retirement, epoch_rebuild, *profiler_additions, *([fault] if fault else []), *([diagnostic] if diagnostic else []), *diagnostic_additions]:
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
                "profiler_base_revision": profiler["revision"], "profiler_base_sum": profiler_info["Sum"], "capture_abi": 2,
                "bpf_builder": lock["images"]["gadget_builder"]}
    profiler_marker = generated_profiler / ".mw-prepared.json"
    if diagnostic:
        manifest["diagnostic"] = "identity_observations"
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
        subprocess.run(["patch", "--batch", "--forward", "--fuzz=0", "-p1", "-i", str(epoch_capture)], cwd=generated_profiler, check=True)
        subprocess.run(["patch", "--batch", "--forward", "--fuzz=0", "-p1", "-i", str(epoch_memory)], cwd=generated_profiler, check=True)
        subprocess.run(["patch", "--batch", "--forward", "--fuzz=0", "-p1", "-i", str(epoch_handler)], cwd=generated_profiler, check=True)
        subprocess.run(["patch", "--batch", "--forward", "--fuzz=0", "-p1", "-i", str(profiler_shutdown)], cwd=generated_profiler, check=True)
        subprocess.run(["patch", "--batch", "--forward", "--fuzz=0", "-p1", "-i", str(profiler_definition_drain)], cwd=generated_profiler, check=True)
        subprocess.run(["patch", "--batch", "--forward", "--fuzz=0", "-p1", "-i", str(profiler_metadata_retirement)], cwd=generated_profiler, check=True)
        for path in profiler_additions:
            dest = generated_profiler / path.relative_to(root / "patches/profiler-additions").with_suffix("")
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, dest)
        if diagnostic:
            for path in diagnostic_additions:
                dest = generated_profiler / path.relative_to(root / "patches/diagnostic-additions").with_suffix("")
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, dest)
            subprocess.run(["patch", "--batch", "--forward", "--fuzz=0", "-p1", "-i", str(diagnostic)], cwd=generated_profiler, check=True)
        subprocess.run(["bash", str(epoch_rebuild), str(generated_profiler)], cwd=root, check=True)
        artifacts = {str(p.relative_to(generated_profiler)): hashlib.sha256(p.read_bytes()).hexdigest()
                     for p in [generated_profiler / "support/types.go", generated_profiler / "support/ebpf/tracer.ebpf.amd64", generated_profiler / "support/ebpf/tracer.ebpf.arm64"]}
        (generated_profiler / ".mw-epoch-artifacts.json").write_text(json.dumps(artifacts, indent=2) + "\n")
        profiler_marker.write_text(json.dumps(manifest, indent=2) + "\n")
    elif json.loads(profiler_marker.read_text()) != manifest:
        raise RuntimeError("Generated profiler provenance mismatch")
    artifact_hashes = json.loads((generated_profiler / ".mw-epoch-artifacts.json").read_text())
    for name, expected in artifact_hashes.items():
        if hashlib.sha256((generated_profiler / name).read_bytes()).hexdigest() != expected:
            raise RuntimeError("Generated epoch artifact mismatch: " + name)
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
        subprocess.run(["patch", "--batch", "--forward", "--fuzz=0", "-p1", "-i", str(gadget_epoch)], cwd=generated, check=True)
        subprocess.run(["patch", "--batch", "--forward", "--fuzz=0", "-p1", "-i", str(gadget_shutdown)], cwd=generated, check=True)
        subprocess.run(["patch", "--batch", "--forward", "--fuzz=0", "-p1", "-i", str(gadget_event_loss)], cwd=generated, check=True)
        subprocess.run(["patch", "--batch", "--forward", "--fuzz=0", "-p1", "-i", str(gadget_finalization)], cwd=generated, check=True)
        subprocess.run(["patch", "--batch", "--forward", "--fuzz=0", "-p1", "-i", str(gadget_definition_drain)], cwd=generated, check=True)
        for path in additions:
            dest = generated / path.relative_to(root / "patches/gadget-additions").with_suffix("")
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, dest)
        if fault:
            subprocess.run(["patch", "--batch", "--forward", "--fuzz=0", "-p1", "-i", str(fault)], cwd=generated, check=True)
        marker.write_text(json.dumps(manifest, indent=2) + "\n")
    elif json.loads(marker.read_text()) != manifest:
        raise RuntimeError("Generated dependency provenance mismatch")
    name = "identity-test" if diagnostic else ("recovery-test" if fault else "experiment")
    modfile = root / "build" / (name + ".go.mod")
    shutil.copyfile(root / "go.mod", modfile)
    shutil.copyfile(root / "go.sum", modfile.with_suffix(".sum"))
    relative = "./" + str(generated.relative_to(root))
    subprocess.run(["go", "mod", "edit", "-modfile=" + str(modfile.relative_to(root)),
                    "-replace=" + module["module"] + "=" + relative], cwd=root, check=True)
    subprocess.run(["go", "mod", "edit", "-modfile=" + str(modfile.relative_to(root)),
                    "-replace=" + profiler["module"] + "=./" + str(generated_profiler.relative_to(root))], cwd=root, check=True)
    manifest_file = "identity-test-manifest.json" if diagnostic else ("recovery-test-manifest.json" if fault else "patch-manifest.json")
    (root / "build" / manifest_file).write_text(json.dumps(manifest, indent=2) + "\n")
    print("Prepared Gadget patch " + patch_id)


if __name__ == "__main__":
    prepare()
