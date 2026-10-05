#!/usr/bin/env python3
"""Explicit rootful operator experimental schema1 qualification; never promotes production."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time
import uuid

JDK = "eclipse-temurin@sha256:119a3d18f160a3e7655a66034d0f43beee31cd7b3b9142d57a5de29772011de6"
GO = "golang@sha256:fb612b7831d53a89cbc0aaa7855b69ad7b0caf603715860cf538df854d047b84"


def attached(path):
    if not path.exists(): return False
    for line in path.read_text().splitlines(keepends=True):
        if line.endswith("\n") and json.loads(line).get("kind") == "collector_attached": return True
    return False


def run(assets, output, docker_host):
    assets, output = Path(assets).absolute(), Path(output).absolute()
    output.mkdir(exist_ok=False)
    docker = ["docker", "--host", docker_host]
    source = Path(__file__).resolve().parents[1] / "fixtures/java"
    classes = output / "classes"; classes.mkdir()
    subprocess.run(docker + ["run", "--pull=never", "--rm", "--user", str(os.getuid()),
        "--mount", f"type=bind,src={source},dst={source},readonly", "--mount", f"type=bind,src={output},dst={output}",
        JDK, "javac", "-d", str(classes), str(source / "RuntimeCandidateFixture.java"), str(source / "VirtualThreadOracle.java")], check=True, timeout=60)
    manifest = json.loads((assets / "manifest.json").read_text())
    image = "ghcr.io/inspektor-gadget/gadget/mw-hotspot-openat@" + manifest["gadget_digest"]
    cases = [("c1-serial", ["-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1"], False),
             ("c2-serial", ["-XX:+UseSerialGC", "-XX:-TieredCompilation"], False),
             ("xint-g1", ["-Xint", "-XX:+UseG1GC"], False)]
    cases += [(f"virtual-carriers-{n}", ["-Xint", "-XX:+UseSerialGC", f"-Djdk.virtualThreadScheduler.parallelism={n}"], True) for n in [1, 2, 4]]
    results = []
    for label, flags, virtual in cases:
        root = output / label; root.mkdir()
        nonce = "mw-qual-" + uuid.uuid4().hex[:12]; target, collector = nonce + "-target", nonce + "-collector"
        child = owner = None
        try:
            argv = docker + ["run", "--pull=never", "--rm", "--name", target, "--user", str(os.getuid()), "--pid=host",
                "--mount", f"type=bind,src={output},dst={output}", JDK, "java", *flags, "-XX:+PrintCompilation", "-cp", str(classes),
                "VirtualThreadOracle" if virtual else "RuntimeCandidateFixture", str(root)]
            if not virtual: argv += ["40"]
            with (root / "target.log").open("w") as target_log:
                child = subprocess.Popen(argv, stdout=target_log, stderr=subprocess.STDOUT)
                deadline = time.monotonic() + 60
                while not (root / "ready").exists():
                    if child.poll() is not None or time.monotonic() >= deadline: raise RuntimeError("target failed readiness")
                    time.sleep(.05)
                pid = int((root / "ready").read_text())
                observed = subprocess.run(docker + ["exec", target, "jcmd", str(pid), "VM.flags", "-all"], capture_output=True, text=True, timeout=20)
                (root / "vm-flags.log").write_text(observed.stdout + observed.stderr)
                with (root / "capture.jsonl").open("w") as stdout, (root / "collector.log").open("w") as stderr:
                    cmd = docker + ["run", "--pull=never", "--rm", "--name", collector, "--privileged", "--pid=host",
                        "--mount", f"type=bind,src={assets},dst={assets},readonly", "--mount", "type=bind,src=/sys,dst=/sys,readonly",
                        GO, str(assets / "mw-hotspot-collector"), "--capture", "--protocol-version=1", "--hotspot-identity=unknown",
                        "--pid", str(pid), "--store", str(assets / "oci"), "--image", image, "--duration", "15s"]
                    owner = subprocess.Popen(cmd, stdout=stdout, stderr=stderr)
                    deadline = time.monotonic() + 10
                    while not attached(root / "capture.jsonl"):
                        if owner.poll() is not None or time.monotonic() >= deadline: raise RuntimeError("native attachment failed readiness")
                        time.sleep(.05)
                    (root / "measure").write_text("measure")
                    owner.wait(timeout=30)
                (root / "release").write_text("release"); child.wait(timeout=10)
            rows = [json.loads(line) for line in (root / "capture.jsonl").read_text().splitlines() if line.strip()]
            verifier = Path(__file__).with_name("verify-loom-candidate.py" if virtual else "verify-runtime-candidate.py")
            spec = importlib.util.spec_from_file_location("candidate_verifier", verifier); module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
            try:
                oracle = {r["token"]: r for r in [json.loads(line) for line in (root / "oracle.jsonl").read_text().splitlines()]} if virtual else json.loads((root / "manifest.json").read_text())
                verdict = module.verify(oracle, rows)
            except ValueError as error:
                verdict = {"oracle_rejected": str(error), "production_qualified": False}
            result = {"label": label, "pid": pid, "flags": flags, "flags_observed_exit": observed.returncode,
                      "collector_exit": owner.returncode, "target_exit": child.returncode, "verdict": verdict, "production_qualified": False}
            (root / "result.json").write_text(json.dumps(result, indent=2) + "\n"); results.append(result)
        finally:
            subprocess.run(docker + ["rm", "-f", collector, target], capture_output=True, timeout=20)
            for process in (owner, child):
                if process is not None:
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill(); process.wait(timeout=10)
    (output / "summary.json").write_text(json.dumps({"jdk_image": JDK, "go_image": GO, "asset_manifest": manifest,
        "experimental_schema": 1, "results": results, "production_qualified": False}, indent=2) + "\n")
    print(json.dumps(results))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument("--assets", required=True); parser.add_argument("--output", required=True)
    parser.add_argument("--docker-host", required=True); run(**vars(parser.parse_args()))
