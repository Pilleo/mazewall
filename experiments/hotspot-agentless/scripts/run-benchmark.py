#!/usr/bin/env python3
"""Serial pinned three-mode runs; measurements carry their actual window."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import secrets
import statistics
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parent.parent
COLLECTOR_BINARY = os.environ.get("MW_COLLECTOR_BINARY", "build/mw-hotspot-demo")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--operations", type=int, default=1200)
    parser.add_argument("--warmup", type=int, default=600)
    parser.add_argument("--post-attach-warmup", type=int, default=0)
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--diversity", type=int, default=1, choices=[1, 8])
    parser.add_argument("--pause-ns", type=int, default=500000)
    parser.add_argument("--gadget-ref", default="build/gadget-image.ref")
    parser.add_argument("--expect-missing", type=int, default=0)
    parser.add_argument("--expect-missing-sequence", type=int)
    parser.add_argument("--modes", default="baseline,events,full")
    parser.add_argument("--drain-events", action="store_true")
    parser.add_argument("--drain-full", action="store_true")
    args = parser.parse_args()
    modes = args.modes.split(",")
    if args.drain_full and (args.drain_events or any(mode not in ["baseline","full"] for mode in modes)):
        parser.error("strict full drain measurements support baseline/full and one owner")
    if args.drain_events and any(mode not in ["baseline", "events"] for mode in modes):
        parser.error("owned event drain measurements support baseline/events modes only")
    if not 1 <= args.operations <= 100000 or not 0 <= args.warmup <= 100000 or not 1 <= args.repeats <= 10 or not 0 <= args.pause_ns <= 10000000 or any(m not in ["baseline", "events", "full"] for m in modes):
        parser.error("invalid bounded benchmark configuration")
    if args.operations * args.pause_ns > 20000000000:
        parser.error("paced workload must fit the 20-second target bound")
    if not 0 <= args.post_attach_warmup <= 10000 or (args.post_attach_warmup and args.post_attach_warmup < args.diversity):
        parser.error("post-attachment preparation must be zero or cover all routes within 10000 operations")
    if args.expect_missing_sequence is not None and (args.expect_missing != 1 or not 0 <= args.expect_missing_sequence < args.operations):
        parser.error("exact missing sequence requires one expected missing operation within the workload")
    lock = json.loads((ROOT / "dependencies.lock.json").read_text())
    manifest = json.loads((ROOT / "build/patch-manifest.json").read_text())
    image = (ROOT / args.gadget_ref).read_text().strip()
    docker = [os.environ.get("DOCKER", "docker")]
    endpoint = os.environ.get("COLLECTOR_DOCKER_HOST") or os.environ.get("DOCKER_HOST")
    if endpoint:
        docker += ["--host", endpoint]
    directory = ROOT / "build" / ("benchmark." + secrets.token_hex(4))
    directory.mkdir()
    reports = []
    for repeat in range(args.repeats):
        order = modes[repeat % len(modes):] + modes[:repeat % len(modes)]
        for mode in order:
            report = run_one(args, mode, repeat, directory, docker, lock, manifest, image)
            reports.append(report)
            print(mode, repeat, "ops/s", round(report["operations_per_second"], 2), "p99_ns", report["target"]["p99_ns"], "missing", report["unobserved_marked_operations"], flush=True)
    aggregate = {"runs": reports, "comparison_semantics": "independent_runs_same_runtime_workload; collector CPU window includes startup/readiness", "collection_loss_complete": False, "kernel_memory_measured": False, "summary": {}}
    for mode in modes:
        selected = [r for r in reports if r["manifest"]["mode"] == mode]
        vals = [r["operations_per_second"] for r in selected]
        aggregate["summary"][mode] = {"runs": len(vals), "throughput_median": statistics.median(vals), "throughput_min": min(vals), "throughput_max": max(vals), "p99_ns_median": statistics.median(r["target"]["p99_ns"] for r in selected)}
    (directory / "benchmark-report.json").write_text(json.dumps(aggregate, indent=2) + "\n")
    print("Benchmark artifacts:", directory, flush=True)


def run_one(args, mode, repeat, directory, docker, lock, patch, image):
    artifact = directory / (mode + "-" + str(repeat))
    artifact.mkdir()
    run = secrets.token_hex(8)
    target = collector = None
    attached_process = None
    stop_sample = threading.Event()
    samples = []
    sample_errors = []
    sampler = None
    output = (artifact / "events.jsonl").open("w")
    errors = (artifact / "collector.log").open("w")
    started = time.monotonic_ns()
    try:
        target = subprocess.check_output(docker + ["run", "-d", "--user", f"{os.getuid()}:{os.getgid()}", "--pid=host", "--mount", f"type=bind,src={ROOT},dst=/work,readonly", "--mount", f"type=bind,src={artifact},dst=/fixture", lock["images"]["jdk"], "java", "-Xint", "-XX:+UseSerialGC", "-Xmx128m", "-cp", "/work/build/benchmark-classes", "BenchmarkFixture", "/fixture", run, str(args.operations), str(args.warmup), str(args.diversity), str(args.pause_ns), str(args.post_attach_warmup)], text=True).strip()
        pid = int(subprocess.check_output(docker + ["inspect", "--format", "{{.State.Pid}}", target], text=True))
        wait_until(lambda: "ready " in subprocess.check_output(docker + ["logs", target], text=True), 25, "target readiness")
        collector_resources = None
        if mode != "baseline":
            command = docker + ["create", "--privileged", "--pid=host", "--mount", "type=bind,src=/sys,dst=/sys,readonly", "--mount", f"type=bind,src={ROOT},dst=/work,readonly", "--mount", f"type=bind,src={os.environ.get('MW_GADGET_STORE', str(Path.home()/'.ig/oci-store'))},dst=/store,readonly", "--entrypoint", "/work/" + COLLECTOR_BINARY, lock["images"]["go"], "--capture", "--pid", str(pid), "--image", image, "--store", "/store", "--duration", "45s"]
            command += ["--collection-mode", "events"] if mode == "events" else ["--hotspot-identity", "safepoint-epoch"]
            if args.drain_events:
                command += ["--drain-events"]
            if args.drain_full:
                command += ["--drain-full"]
            collector = subprocess.check_output(command, text=True).strip()
            attached_process = subprocess.Popen(docker + ["start", "--attach", collector], stdout=output, stderr=errors)
            wait_until(lambda: subprocess.check_output(docker + ["inspect", "--format", "{{.State.Running}}", collector], text=True).strip() == "true", 10, "collector process")
            cpid = int(subprocess.check_output(docker + ["inspect", "--format", "{{.State.Pid}}", collector], text=True))
            sample_start = time.monotonic_ns()

            def sample():
                try:
                    tail = Path(f"/proc/{cpid}/stat").read_text().rsplit(")", 1)[1].split()
                    status = Path(f"/proc/{cpid}/status").read_text()
                    rss = next(int(line.split()[1]) for line in status.splitlines() if line.startswith("VmRSS:"))
                    samples.append((time.monotonic_ns(), int(tail[11]) + int(tail[12]), rss))
                except FileNotFoundError:
                    sample_errors.append("process_disappeared")
                except Exception as exc:
                    sample_errors.append(repr(exc))

            def sampling():
                while not stop_sample.is_set():
                    sample()
                    stop_sample.wait(.02)

            sampler = threading.Thread(target=sampling)
            sampler.start()
            wait_until(lambda: '"collector_attached"' in (artifact / "events.jsonl").read_text(), 25, "collector attachment")
        readiness = time.monotonic_ns() - started
        data = {"mode": mode, "run": run, "host_pid": pid, "target_uid": os.getuid(), "operations": args.operations, "warmup": args.warmup, "post_attach_warmup": args.post_attach_warmup, "preparation_pause_ns": 500000, "diversity": args.diversity, "pause_ns": args.pause_ns, "jvm_image": lock["images"]["jdk"], "jvm_flags": ["-Xint", "-XX:+UseSerialGC", "-Xmx128m"], "patch_sha256": patch["patch_sha256"], "gadget_image": image, "kernel": platform.release(), "readiness_ns": readiness, "readiness_semantics": "target_ready_and_collector_attached; not_enrichment_readiness", "target_agent_injected": False, "source_sha256": hashlib.sha256((ROOT / "fixtures/java/BenchmarkFixture.java").read_bytes()).hexdigest(), "class_sha256": hashlib.sha256((ROOT / "build/benchmark-classes/BenchmarkFixture.class").read_bytes()).hexdigest()}
        if mode != "baseline":
            data["collector_binary_sha256"] = hashlib.sha256((ROOT / COLLECTOR_BINARY).read_bytes()).hexdigest()
        data["drain_events"] = args.drain_events and mode == "events"
        data["drain_full"] = args.drain_full and mode == "full"
        (artifact / "benchmark-manifest.json").write_text(json.dumps(data, indent=2) + "\n")
        if args.post_attach_warmup:
            preparation_start = time.monotonic_ns()
            (artifact / "prepare").touch()
            wait_until(lambda: (artifact / "preparation-target.json").exists(), 15, "post-attachment preparation")
            if mode == "full":
                def ready():
                    result = subprocess.run([str(ROOT / "build/mw-benchmark-report"), "--readiness", str(artifact)], text=True, capture_output=True, check=False)
                    if result.returncode != 0:
                        (artifact / "readiness-error.log").write_text(result.stderr)
                        return False
                    observation = json.loads(result.stdout)
                    (artifact / "readiness-before-measurement.json").write_text(json.dumps(observation, indent=2) + "\n")
                    return observation["ready"]
                wait_until(ready, 5, "epoch-checked preparation routes")
            data["post_attachment_preparation_ns"] = time.monotonic_ns() - preparation_start
            (artifact / "benchmark-manifest.json").write_text(json.dumps(data, indent=2) + "\n")
        (artifact / "gate").touch()
        wait_until(lambda: (artifact / "benchmark-target.json").exists(), 25, "target measurement")
        if mode != "baseline":
            stop_sample.set()
            sampler.join()
            if sample_errors or len(samples) < 2:
                raise RuntimeError("incomplete collector resource samples: " + str(sample_errors))
            collector_resources = {"cpu_seconds": (samples[-1][1] - samples[0][1]) / os.sysconf("SC_CLK_TCK"), "peak_rss_kib": max(s[2] for s in samples), "samples": len(samples), "window_ns": samples[-1][0] - samples[0][0], "semantics": "collector_process_attach_to_target_done"}
            (artifact / "resource-samples.json").write_text(json.dumps({"host_pid": cpid, "clock_ticks_per_second": os.sysconf("SC_CLK_TCK"), "samples": samples, "errors": sample_errors}, indent=2) + "\n")
            stop_started = time.monotonic_ns()
            subprocess.run(docker + ["kill", "--signal", "SIGINT", collector], check=True, stdout=subprocess.DEVNULL)
            attached_process.wait(timeout=10)
            data["post_target_stop_ns"] = time.monotonic_ns() - stop_started
            data["post_target_stop_semantics"] = "docker_signal_request_to_collector_exit; excludes_target_workload"
            code = int(subprocess.check_output(docker + ["inspect", "--format", "{{.State.ExitCode}}", collector], text=True))
            if code != 0:
                raise RuntimeError("collector failed: " + (artifact / "collector.log").read_text())
        (artifact / "finish").touch()
        if int(subprocess.check_output(docker + ["wait", target], text=True, timeout=10)) != 0:
            raise RuntimeError("target failed")
        output.close()
        errors.close()
        if collector_resources is not None:
            data["collector_resources"] = collector_resources
        (artifact / "benchmark-manifest.json").write_text(json.dumps(data, indent=2) + "\n")
        raw = subprocess.check_output([str(ROOT / "build/mw-benchmark-report"), str(artifact)], text=True)
        report = json.loads(raw)
        (artifact / "run-report.json").write_text(json.dumps(report, indent=2) + "\n")
        validate_report(args, mode, report)
        return report
    finally:
        stop_sample.set()
        if sampler:
            sampler.join(timeout=2)
        for owned in [collector, target]:
            if owned:
                subprocess.run(docker + ["rm", "-f", owned], stdout=subprocess.DEVNULL, check=True)
        if attached_process and attached_process.poll() is None:
            attached_process.wait(timeout=5)
        output.close()
        errors.close()


def validate_report(args, mode, report):
    if mode != "baseline" and report["unobserved_marked_operations"] != args.expect_missing:
        raise RuntimeError("unexplained benchmark missing operations: " + str(report.get("missing_sequences", [])))
    if mode != "baseline" and args.expect_missing_sequence is not None and report.get("missing_sequences", []) != [args.expect_missing_sequence]:
        raise RuntimeError("event-loss control did not observe exact missing token: " + str(report.get("missing_sequences", [])))
    if mode == "full" and report["correct_application_segments"] == 0:
        raise RuntimeError("no useful full-mode enrichment")


def wait_until(predicate, seconds, name):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(.02)
    raise TimeoutError(name + " did not complete within bound")


if __name__ == "__main__":
    main()
