#!/usr/bin/env python3
"""Executable checks of the real Gradle bootstrap; no generator/backend substitution."""
import fcntl
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[3]
LOCK = Path("/home/leanid/Documents/code/java/jseccomp/.superpowers/sdd/2026-10-06-mazewall-integration-foundation-plan/verification.lock")
EVIDENCE = Path(tempfile.mkdtemp(prefix="koog-bootstrap-tests."))
RECEIPT = ROOT / "build/portal-codegen/plugin-classpath.properties"
PLUGIN = ROOT / "portals/codegen/build/libs/codegen-0.1.0-SNAPSHOT.jar"
WRAPPER = ["bash", str(ROOT / "scripts/koog-build.sh"), "--console=plain"]
DIRECT = [str(ROOT / "gradlew"), "--console=plain", "--no-configuration-cache", "-PincludeKoogIntegration=true"]
SEQUENCE = 0


def run(label, command, expected=0, message=None):
    global SEQUENCE
    SEQUENCE += 1
    output = EVIDENCE / f"{SEQUENCE:02d}-{label}.log"
    with output.open("w") as stream:
        result = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, timeout=240)
    text = output.read_text()
    assert result.returncode == expected, (label, result.returncode, output)
    if message is not None:
        assert message in text, (label, message, output)
    print(f"BOOTSTRAP_CASE_OK {label} status={result.returncode} log={output}", flush=True)
    return text


def check():
    print(f"bootstrap_test_evidence={EVIDENCE}", flush=True)
    default = run("default-core", [str(ROOT / "gradlew"), "--console=plain", "--no-configuration-cache", "projects"])
    assert ":integrations:koog" not in default and ":demos:koog-contained-tool" not in default
    run("normal-producer-and-demo", WRAPPER + ["--", ":demos:koog-contained-tool:portalCheck"])
    assert b"source.buildSrc/build.gradle=" in RECEIPT.read_bytes()
    assert b"source.profiler/src/main/" in RECEIPT.read_bytes()

    # A real new/modified/deleted producer resource must reject direct loading and
    # be incorporated/removed by the actual producer JAR task on wrapper refresh.
    resource = ROOT / "portals/codegen/src/main/resources/task10-freshness-probe.txt"
    assert not resource.exists()
    created_parent = not resource.parent.exists()
    resource.parent.mkdir(exist_ok=True)
    try:
        resource.write_text("actual producer resource v1\n")
        run("added-source-rejected", DIRECT + ["help"], 1, "Stale Portal plugin producer sources")
        run("added-source-refreshed", WRAPPER + ["--", ":demos:koog-contained-tool:portalCheck"])
        with zipfile.ZipFile(PLUGIN) as jar:
            assert jar.read(resource.name) == resource.read_bytes()
        resource.write_text("actual producer resource v2\n")
        run("modified-source-rejected", DIRECT + ["help"], 1, "Stale Portal plugin producer sources")
        resource.unlink()
        run("removed-source-rejected", DIRECT + ["help"], 1, "Stale Portal plugin producer sources")
    finally:
        if resource.exists():
            resource.unlink()
        if created_parent:
            resource.parent.rmdir()
        run("source-removal-refreshed", WRAPPER + ["--", ":demos:koog-contained-tool:portalCheck"])
    with zipfile.ZipFile(PLUGIN) as jar:
        assert resource.name not in jar.namelist()

    original = RECEIPT.read_bytes()
    assert b"version=0.1.0-SNAPSHOT" in original
    try:
        RECEIPT.write_bytes(original.replace(b"version=0.1.0-SNAPSHOT", b"version=0.1.0-stale"))
        run("version-rejected", DIRECT + ["help"], 1, "Stale Portal plugin producer version")
        RECEIPT.write_bytes(original.replace(str(ROOT).encode(), b"/different/worktree"))
        run("other-worktree-rejected", DIRECT + ["help"], 1, "Portal plugin receipt belongs")
        RECEIPT.unlink()
        run("missing-receipt-rejected", DIRECT + ["help"], 1, "Build the actual Portal plugin first")
    finally:
        RECEIPT.write_bytes(original)

    # Change the effective root/compiled artifact version through the existing
    # repository/version staging selector, rather than editing coordinator files.
    stage = str(ROOT / "tests/consumers/stage.init.gradle")
    version_options = ["-I", stage, "-PconsumerVersion=0.1.0-task10-freshness",
                       f"-PconsumerRepository={EVIDENCE / 'repository'}"]
    run("changed-build-version-rejected", DIRECT + version_options + ["help"], 1,
        "Stale Portal plugin producer version")
    run("changed-build-version-refreshed", WRAPPER + version_options + ["--", ":demos:koog-contained-tool:portalCheck"])
    assert b"version=0.1.0-task10-freshness" in RECEIPT.read_bytes()
    run("default-build-version-refreshed", WRAPPER + ["--", ":demos:koog-contained-tool:portalCheck"])
    original = RECEIPT.read_bytes()

    backup = PLUGIN.with_suffix(".jar.task10-test-hidden")
    assert not backup.exists()
    identity = (PLUGIN.stat().st_dev, PLUGIN.stat().st_ino)
    os.rename(PLUGIN, backup)
    replacement_identity = None
    try:
        run("missing-actual-jar-rejected", DIRECT + ["help"], 1, "Stale/missing Portal plugin artifact")
        changed = bytearray(backup.read_bytes())
        changed[-1] ^= 1
        with PLUGIN.open("xb") as stream:
            stream.write(changed)
        replacement_identity = (PLUGIN.stat().st_dev, PLUGIN.stat().st_ino)
        run("changed-actual-jar-rejected", DIRECT + ["help"], 1, "Stale/missing Portal plugin artifact")
    finally:
        if replacement_identity is not None:
            assert (PLUGIN.stat().st_dev, PLUGIN.stat().st_ino) == replacement_identity
            PLUGIN.unlink()
        assert not PLUGIN.exists(), f"Preserve conflicting output and recoverable backup: {backup}"
        assert (backup.stat().st_dev, backup.stat().st_ino) == identity
        os.rename(backup, PLUGIN)

    failure = EVIDENCE / "producer-failure.init.gradle"
    failure.write_text("settingsEvaluated { settings ->\n"
                       " if (settings.startParameter.projectProperties['koogBootstrap'] == 'true')\n"
                       "  throw new GradleException('TASK10_ACTUAL_PRODUCER_FAILURE')\n}\n")
    failed = run("producer-failure-propagates", WRAPPER + ["-I", str(failure), "--", "help"], 1,
                 "TASK10_ACTUAL_PRODUCER_FAILURE")
    assert "KOOG_STAGE selected" not in failed
    assert RECEIPT.read_bytes() == original
    failed = run("selected-failure-propagates", WRAPPER + ["--", ":demos:koog-contained-tool:task10MissingTask"], 1,
                 "task10MissingTask")
    assert "KOOG_STAGE selected" in failed
    run("configuration-cache-rejected", [str(ROOT / "gradlew"), "--console=plain", "--configuration-cache",
                                         "-PincludeKoogIntegration=true", "help"], 1,
        "Koog source bootstrap requires uncached receipt validation")
    run("final-real-generator", WRAPPER + ["--", ":demos:koog-contained-tool:jar", ":demos:koog-contained-tool:portalCheck"])


with LOCK.open("a") as lock:
    fcntl.flock(lock, fcntl.LOCK_EX)
    check()
