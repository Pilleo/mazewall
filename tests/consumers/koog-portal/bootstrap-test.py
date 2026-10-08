#!/usr/bin/env python3
"""Executable checks of the real Gradle bootstrap; no generator/backend substitution."""
import fcntl
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[3]
LOCK = Path("/home/leanid/Documents/code/java/jseccomp/.superpowers/sdd/2026-10-06-mazewall-integration-foundation-plan/verification.lock")
EVIDENCE_PARENT = ROOT / "build/task10-evidence/round1"
EVIDENCE_PARENT.mkdir(parents=True, exist_ok=True)
EVIDENCE = Path(tempfile.mkdtemp(prefix="koog-bootstrap-tests.", dir=EVIDENCE_PARENT))
spec = importlib.util.spec_from_file_location("fixture_files", Path(__file__).with_name("fixture-files.py"))
owned = importlib.util.module_from_spec(spec)
spec.loader.exec_module(owned)
RECEIPT = ROOT / "build/portal-codegen/plugin-classpath.properties"
PLUGIN = ROOT / "portals/codegen/build/libs/codegen-0.1.0-SNAPSHOT.jar"
WRAPPER = ["bash", str(ROOT / "scripts/koog-build.sh"), "--console=plain"]
DIRECT = [str(ROOT / "gradlew"), "--console=plain", "--no-configuration-cache", "-PincludeKoogIntegration=true"]
SEQUENCE = 0


def run(label, command, expected=0, message=None):
    global SEQUENCE
    SEQUENCE += 1
    output = EVIDENCE / f"{SEQUENCE:02d}-{label}.log"
    with output.open("x") as stream:
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
    if created_parent:
        resource.parent.mkdir()
    resource_file = owned.OwnedFile(resource, "actual producer resource v1\n", EVIDENCE / "resource-recovery.txt")
    try:
        run("added-source-rejected", DIRECT + ["help"], 1, "Stale Portal plugin producer sources")
        run("added-source-refreshed", WRAPPER + ["--", ":demos:koog-contained-tool:portalCheck"])
        with zipfile.ZipFile(PLUGIN) as jar:
            assert jar.read(resource.name) == resource.read_bytes()
        resource_file.write("actual producer resource v2\n")
        run("modified-source-rejected", DIRECT + ["help"], 1, "Stale Portal plugin producer sources")
        resource_file.retire()
        resource_file = None
        run("removed-source-rejected", DIRECT + ["help"], 1, "Stale Portal plugin producer sources")
    finally:
        if resource_file is not None:
            resource_file.retire()
        # Keep the empty directory: its pathname could have been replaced by an
        # unknown actor, and no removal is needed for the exact file inventory.
        run("source-removal-refreshed", WRAPPER + ["--", ":demos:koog-contained-tool:portalCheck"])
    with zipfile.ZipFile(PLUGIN) as jar:
        assert resource.name not in jar.namelist()

    original = RECEIPT.read_bytes()
    assert b"version=0.1.0-SNAPSHOT" in original
    receipt_backup = owned.BorrowedFile(RECEIPT, EVIDENCE / "original-receipt.properties")
    receipt_file = None
    try:
        receipt_file = owned.OwnedFile(RECEIPT, original, EVIDENCE / "mutated-receipt.properties")
        receipt_file.write(original.replace(b"version=0.1.0-SNAPSHOT", b"version=0.1.0-stale"))
        run("version-rejected", DIRECT + ["help"], 1, "Stale Portal plugin producer version")
        receipt_file.write(original.replace(str(ROOT).encode(), b"/different/worktree"))
        run("other-worktree-rejected", DIRECT + ["help"], 1, "Portal plugin receipt belongs")
        receipt_file.retire()
        receipt_file = None
        run("missing-receipt-rejected", DIRECT + ["help"], 1, "Build the actual Portal plugin first")
    finally:
        owned.restore_all(*(([receipt_file.retire] if receipt_file is not None else []) + [receipt_backup.restore]))

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

    plugin_backup = owned.BorrowedFile(PLUGIN, EVIDENCE / "original-plugin.jar")
    plugin_file = None
    try:
        run("missing-actual-jar-rejected", DIRECT + ["help"], 1, "Stale/missing Portal plugin artifact")
        changed = bytearray(plugin_backup.data)
        changed[-1] ^= 1
        plugin_file = owned.OwnedFile(PLUGIN, changed, EVIDENCE / "mutated-plugin.jar")
        run("changed-actual-jar-rejected", DIRECT + ["help"], 1, "Stale/missing Portal plugin artifact")
    finally:
        owned.restore_all(*(([plugin_file.retire] if plugin_file is not None else []) + [plugin_backup.restore]))

    failure = EVIDENCE / "producer-failure.init.gradle"
    failure_content = ("settingsEvaluated { settings ->\n"
                       " if (settings.startParameter.projectProperties['koogBootstrap'] == 'true')\n"
                       "  throw new GradleException('TASK10_ACTUAL_PRODUCER_FAILURE')\n}\n")
    with failure.open("x") as stream:
        stream.write(failure_content)
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
