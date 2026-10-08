#!/usr/bin/env python3
"""External private known-fixture authority and disposable finished-JAR acceptance.

Run under the fleet verification lock. This is not a production approval service.
Only a newly created private deployment is touched; arbitrary existing roots are not accepted.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def launch(java, root, mode, success=True, log_name=None):
    command = [java, "--enable-native-access=ALL-UNNAMED", "-jar", "application.jar", mode, str(root)]
    result = subprocess.run(command, cwd=root, text=True, capture_output=True, timeout=90)
    (root / f"{log_name or mode}.log").write_text(result.stdout + result.stderr)
    (root / f"{log_name or mode}.command.json").write_text(json.dumps(
        {"argv": command, "cwd": str(root), "exitCode": result.returncode}, indent=2))
    if success and result.returncode != 0:
        raise AssertionError(f"{mode} failed ({result.returncode}); log={root / (mode + '.log')}\n{result.stdout}\n{result.stderr}")
    if not success and result.returncode == 0:
        raise AssertionError(f"{mode} unexpectedly succeeded")
    return result.stdout + result.stderr


def source_identity(revision):
    workspace = Path(__file__).resolve().parents[3]
    producer_paths = ["platform", "enforcer", "integrations/spring/mazewall-spring-boot",
                      "integrations/spring/mazewall-spring-boot-starter", "buildSrc", "build.gradle.kts",
                      "settings.gradle.kts", "gradle", "gradle.properties"]
    actual_revision = subprocess.check_output(["git", "rev-parse", revision + "^{commit}"], cwd=workspace, text=True).strip()
    subprocess.run(["git", "diff", "--exit-code", actual_revision, "--", *producer_paths], cwd=workspace, check=True, capture_output=True)
    unknown = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard", "--", *producer_paths], cwd=workspace, text=True)
    assert not unknown, f"Unidentified producer source inputs: {unknown}"
    tracked = subprocess.check_output(["git", "ls-files", "--", *producer_paths], cwd=workspace, text=True).splitlines()
    producer = {path: sha(workspace / path) for path in tracked if (workspace / path).is_file()}
    demo = workspace / "demos" / "spring-boot-sandbox"
    demo_files = [demo / "build.gradle", demo / "settings.gradle", *sorted((demo / "src").rglob("*.java"))]
    consumer_files = [Path(__file__).resolve(), Path(__file__).resolve().with_name("optional-modules.init.gradle")]
    application = {str(path.relative_to(workspace)): sha(path) for path in demo_files + consumer_files}
    return {"producerRevision": actual_revision, "producerSourceSha256": hashlib.sha256(
        json.dumps(producer, sort_keys=True).encode()).hexdigest(), "producerInputs": producer,
        "demoAndHarnessInputs": application,
        "demoAndHarnessSourceSha256": hashlib.sha256(json.dumps(application, sort_keys=True).encode()).hexdigest()}


def verify(jar, java, evidence, revision, version, repository):
    assert jar.is_file(), f"Missing actual finished Boot executable: {jar}"
    source = source_identity(revision)
    publications = {}
    with zipfile.ZipFile(jar) as archive:
        manifest = archive.read("META-INF/MANIFEST.MF").decode()
        assert "Main-Class: org.springframework.boot.loader.launch.JarLauncher" in manifest
        assert "Spring-Boot-Version: 4.1.1" in manifest
        assert "BOOT-INF/classes/demo/boot/ReportService.class" in archive.namelist()
        assert any(p.startswith("BOOT-INF/lib/mazewall-spring-boot-starter-") for p in archive.namelist())
        assert not any("spring-security" in p for p in archive.namelist()), "This fixture advertises Security absence"
        for artifact in ["platform", "enforcer", "mazewall-spring-boot", "mazewall-spring-boot-starter"]:
            published = repository / "io" / "mazewall" / artifact / version / f"{artifact}-{version}.jar"
            assert published.is_file(), f"Missing actual Maven publication: {published}"
            nested = f"BOOT-INF/lib/{artifact}-{version}.jar"
            assert hashlib.sha256(archive.read(nested)).hexdigest() == sha(published), f"Source substitution for {artifact}"
            publications[artifact] = {"path": str(published.resolve()), "sha256": sha(published)}
    root = Path(tempfile.mkdtemp(prefix="task13-boot-deployment-"))
    print(f"DEPLOYMENT={root}", flush=True)
    # No producer output/classpath siblings: java -jar receives only this copied executable.
    shutil.copyfile(jar, root / "application.jar")
    (root / "template.txt").write_text("Reviewed quarterly template")
    (root / "sibling.txt").write_text("Unapproved sibling secret")
    (root / "reports").mkdir()
    (root / "reports" / "quarterly.txt").write_text("")
    launch(java, root, "measure")
    missing = launch(java, root, "verify", success=False, log_name="missing-review")
    assert "trusted-review.properties" in missing, "Absent authority must fail before bootstrap/body"
    candidate = json.loads((root / "candidate.json").read_text())
    # Review authority is external to the implementation JVM, after final packaging.
    # This private fixture explicitly approves this fixed known source/deployment only.
    authority = {
        "jarSha256": sha(root / "application.jar"),
        "documentSha256": sha(root / "candidate.json"),
        "resourceGeneration": (root / "candidate-generation.txt").read_text(),
        "reviewer": "task13-external-private-known-fixture-reviewer",
        "producerRevision": revision,
        "consumerVersion": version,
    }
    assert authority["jarSha256"] in json.dumps(candidate), "Candidate must bind whole final outer JAR"
    review = root / "trusted-review.properties"
    review.write_text("# External private test authority; never application self-approval\n" +
                      "".join(f"{key}={value}\n" for key, value in authority.items()))
    original = review.read_text()
    review.write_text(original.replace(authority["jarSha256"], "0" * 64))
    stale = launch(java, root, "stale", success=False)
    assert "review" in stale.lower() or "mismatch" in stale.lower(), "Actual stale review rejection required"
    review.write_text(original)
    deployed_jar = root / "application.jar"
    original_jar = deployed_jar.read_bytes()
    try:
        with zipfile.ZipFile(deployed_jar, "a") as archive:
            archive.comment = b"private-negative-fixture-changed-finished-jar"
        tampered = launch(java, root, "verify", success=False, log_name="changed-actual-jar")
        assert "Actual finished Boot deployment differs from external trusted review" in tampered
    finally:
        deployed_jar.write_bytes(original_jar)  # Same owned private inode, exact original bytes.
    template = root / "template.txt"
    try:
        template.write_text("unapproved deployment mutation")
        changed = launch(java, root, "verify", success=False, log_name="changed-template")
        assert "Actual finished Boot deployment differs from external trusted review" in changed
    finally:
        template.write_text("Reviewed quarterly template")
    launch(java, root, "measure-unsupported")
    unsupported_authority = {**authority,
        "documentSha256": sha(root / "unsupported-candidate.json"),
        "resourceGeneration": (root / "unsupported-generation.txt").read_text()}
    (root / "unsupported-review.properties").write_text("# Negative fixture approval only\n" +
        "".join(f"{key}={value}\n" for key, value in unsupported_authority.items()))
    rejected = launch(java, root, "unsupported", success=False)
    assert "UNSUPPORTED_REQUIRED_REJECTED bodyEntries=0" in rejected
    assert "Unproxyable required declaration" in rejected or "unproxyable method" in rejected
    output = launch(java, root, "verify")
    for marker in ["BOOT_SECURITY_ABSENT", "REQUIRED_UNBOUND_REJECTED", "BOOT_ACTUAL_ORIGIN",
                   "TRUSTED_INIT_JDBC_WARMUP", "VIRTUAL_CALLER_PLATFORM_BODY", "JDBC_COMMITTED",
                   "DENIED_TEMPLATE_MUTATION", "DENIED_SIBLING_READ", "CALLER_DELIVERY_NOT_PHYSICAL_END",
                   "PHYSICAL_WORKER_RETIRED", "BOOT_NATIVE_PASS"]:
        assert marker in output, f"Missing required actual acceptance marker: {marker}"
    assert (root / "template.txt").read_text() == "Reviewed quarterly template"
    assert (root / "reports" / "quarterly.txt").read_text() == "Reviewed quarterly template: approved report"
    assert sha(root / "application.jar") == authority["jarSha256"]
    evidence.mkdir(parents=True, exist_ok=False)
    for name in ["measure.log", "missing-review.log", "stale.log", "changed-actual-jar.log", "changed-template.log", "verify.log", "candidate.json", "candidate-generation.txt", "trusted-review.properties",
                 "measure-unsupported.log", "unsupported.log", "unsupported-candidate.json", "unsupported-generation.txt", "unsupported-review.properties"]:
        shutil.copyfile(root / name, evidence / name)
    for command in root.glob("*.command.json"):
        shutil.copyfile(command, evidence / command.name)
    (evidence / "source-identity.json").write_text(json.dumps(source, indent=2, sort_keys=True))
    (evidence / "identity.json").write_text(json.dumps({**authority, "deployment": str(root),
        "sidecarSha256": sha(review), "sourceJar": str(jar.resolve()), "publications": publications,
        "producerSourceSha256": source["producerSourceSha256"], "demoAndHarnessSourceSha256": source["demoAndHarnessSourceSha256"],
        "security": "absent", "mode": "non-web"}, indent=2))
    print(f"BOOT_CONSUMER_PASS evidence={evidence} sidecarSha256={sha(review)}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--java", required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--repository", type=Path, required=True)
    options = parser.parse_args()
    verify(options.jar.resolve(), options.java, options.evidence.resolve(), options.revision, options.version, options.repository.resolve())
