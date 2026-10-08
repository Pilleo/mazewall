#!/usr/bin/env python3
"""Real changed Kotlin source must not be certified when its producer is excluded."""
import fcntl
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
spec = importlib.util.spec_from_file_location("fixture_files", HERE / "fixture-files.py")
files = importlib.util.module_from_spec(spec)
spec.loader.exec_module(files)
evidence_parent = ROOT / "build/task10-evidence/round1"
evidence_parent.mkdir(parents=True, exist_ok=True)
evidence = Path(tempfile.mkdtemp(prefix="koog-exclusion-tests.", dir=evidence_parent))
lock = ROOT.parents[1] / ".superpowers/sdd/2026-10-06-mazewall-integration-foundation-plan/verification.lock"


def run(name, args):
    output = evidence / f"{name}.log"
    with output.open("x") as stream:
        result = subprocess.run(args, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, timeout=120)
    return result.returncode, output.read_text()


with lock.open("a") as held:
    fcntl.flock(held, fcntl.LOCK_EX)
    print(f"exclusion_test_evidence={evidence}", flush=True)
    receipt = ROOT / "build/portal-codegen/plugin-classpath.properties"
    producer = [str(ROOT / "gradlew"), "--console=plain", "--no-configuration-cache",
                "-PkoogBootstrap=true", "-PincludeKoogIntegration=true", ":portals:codegen:exportPluginClasspath"]
    status, text = run("baseline", producer)
    assert status == 0, text
    original = receipt.read_bytes()
    source = files.OwnedFile(ROOT / "portals/codegen/src/main/kotlin/Task10ExcludedCompileProbe.kt",
                             "package io.mazewall.portal.codegen\n\nclass Task10ExcludedCompileProbe\n",
                             evidence / "source-recovery.kt")
    try:
        status, text = run("direct-excluded-compile", producer + ["--exclude-task=:portals:codegen:compileKotlin"])
        with zipfile.ZipFile(ROOT / "portals/codegen/build/libs/codegen-0.1.0-SNAPSHOT.jar") as jar:
            absent = "io/mazewall/portal/codegen/Task10ExcludedCompileProbe.class" not in jar.namelist()
        print(f"ACTUAL_EXCLUDED_COMPILE status={status} changed_class_absent={absent}", flush=True)
        assert status != 0 and receipt.read_bytes() == original, "Excluded real compilation issued a new-source receipt over an old JAR"
        skip = evidence / "skip-producer.init.gradle"
        with skip.open("x") as stream:
            stream.write("allprojects { tasks.configureEach { if (path == ':portals:codegen:compileKotlin') onlyIf { false } } }\n")
        status, text = run("actual-onlyif-skip", producer + ["-I", str(skip)])
        assert status != 0 and "producer task did not execute" in text and receipt.read_bytes() == original, text
        for option in (["--exclude-task=:portals:codegen:compileKotlin"], ["-x", ":portals:codegen:compileKotlin"],
                       ["-x:portals:codegen:compileKotlin"], ["--exclude-task", ":portals:codegen:jar"]):
            for position in ("shared", "selected"):
                args = ["bash", str(ROOT / "scripts/koog-build.sh"), "--console=plain"]
                args += option + ["--", "help"] if position == "shared" else ["--", "help"] + option
                status, text = run(f"wrapper-{position}-{len(list(evidence.iterdir()))}", args)
                assert status == 64 and "KOOG_STAGE selected" not in text and receipt.read_bytes() == original, text
        print("PRODUCER_EXCLUSION_CASES_OK direct+8-wrapper", flush=True)
    finally:
        source.retire()
        status, text = run("normal-refresh", producer)
        assert status == 0, text
