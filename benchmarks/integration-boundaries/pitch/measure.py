#!/usr/bin/env python3
"""Small bounded application companion to the boundary harness; no load or disclosure claims."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from collections import Counter
import re

MODES = {"reader-direct", "reader-thread", "outline-direct", "outline-portal", "spring-http"}


def decode(text):
    def fields(pairs):
        result = dict(pairs)
        if len(result) != len(pairs):
            raise ValueError("Duplicate evidence field")
        return result
    return json.loads(text, object_pairs_hook=fields)


def validate(rows):
    def require(condition, message):
        if not condition:
            raise ValueError(message)
    require(bool(rows), "No application observations")
    counts = Counter(row.get("mode") for row in rows)
    require(set(counts) in [MODES - {"spring-http"}, {"spring-http"}] and all(count == 3 for count in counts.values()), "Complete selected application inventory and exactly three fresh forks required")
    require(len({row.get("pid") for row in rows}) == len(rows), "Duplicate fork PID")
    for row in rows:
        require(row.get("schema") == 1 and row.get("warmups") == 20, "Unexpected schema/warmups")
        values = row.get("samples", [])
        require(len(values) == 100 and all(type(value) is int and value > 0 for value in values), "Exactly100 positive serial samples required")
        ordered = sorted(values)
        require(row.get("medianNs") == (ordered[49] + ordered[50]) // 2 and row.get("p95Ns") == ordered[94], "Derived statistics disagree with raw observations")
        require(type(row.get("setupNs")) is int and row["setupNs"] > 0, "Missing startup observation")
        require(type(row.get("drainNs")) is int and row["drainNs"] >= 0, "Missing drain observation")
        require(bool(row.get("scope")), "Measurement scope required")
        require(all(isinstance(row.get(key), str) and row[key] for key in ("java", "os", "arch")), "Actual runtime/environment required")
        artifacts = row.get("artifacts", {})
        require(bool(artifacts) and all(re.fullmatch(r"[0-9a-f]{64}", value) for value in artifacts.values()), "Actual artifact hashes required")
        for key in ("resourcesBefore", "resourcesAfter"):
            snapshot = row.get(key, {})
            require(snapshot.get("pid") == row["pid"] and all(type(snapshot.get(k)) is int and snapshot[k] > 0 for k in ("rssKiB", "threads")), "Missing actual resource snapshot")
        direct = row["mode"].endswith("direct")
        require(row.get("retirement") == ("not-applicable" if direct else "physical"), "Caller delivery is not physical retirement")
        if row["mode"] in {"reader-thread", "outline-portal"}:
            pressure = row.get("pressure") or {}
            require(pressure.get("accepted") == 4 and pressure.get("rejected") == 1 and pressure.get("rejectionNs", 0) > 0, "Bounded pressure control required separately from latency")
        if row["mode"] == "outline-portal":
            child = row.get("childResources") or {}
            require(type(child.get("pid")) is int and child["pid"] > 0 and child["pid"] != row["pid"]
                    and all(type(child.get(key)) is int and child[key] > 0 for key in ("rssKiB", "threads")), "Actual distinct child identity/RSS/threads required")


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def java():
    executable = shutil.which("java")
    if not executable:
        raise ValueError("JDK25 java executable required on PATH")
    return executable


def exit_status(process):
    # WNOWAIT retains the genuinely acquired leader PID until every group member is stopped.
    return os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)


def await_exit(process, timeout):
    deadline = time.monotonic() + timeout
    while True:
        status = exit_status(process)
        if status is not None:
            return status.si_status if status.si_code == os.CLD_EXITED else -status.si_status
        if time.monotonic() >= deadline:
            raise subprocess.TimeoutExpired(process.args, timeout)
        time.sleep(.01)


def live_group(leader):
    members = []
    for entry in Path('/proc').iterdir():
        if not entry.name.isdecimal():
            continue
        try:
            fields = (entry / 'stat').read_bytes().rsplit(b')', 1)[1].split()
        except (FileNotFoundError, ProcessLookupError):
            continue
        if int(fields[2]) == leader and int(fields[3]) == leader and fields[0] not in {b'Z', b'X'}:
            members.append(int(entry.name))
    return members


def _require_owned_group(process):
    exit_status(process)  # A reaped/reused numeric PID is not authority to signal a group.
    if os.getpgid(process.pid) != process.pid or os.getsid(process.pid) != process.pid:
        raise ValueError('Acquired private process group/session required')


def retire(process):
    _require_owned_group(process)
    kill_deadline = None
    try:
        remaining = live_group(process.pid)
        for sig, timeout in ((signal.SIGTERM, 2), (signal.SIGKILL, 5)):
            if not remaining:
                break
            deadline = time.monotonic() + timeout
            if sig == signal.SIGKILL:
                kill_deadline = deadline
            os.killpg(process.pid, sig)
            while remaining and time.monotonic() < deadline:
                time.sleep(.01)
                remaining = live_group(process.pid)
    except BaseException as failure:
        try:
            _require_owned_group(process)
            # Unreadable process state must fail the run, not abandon its known owned group.
            if kill_deadline is None:
                kill_deadline = time.monotonic() + 5
            os.killpg(process.pid, signal.SIGKILL)
            await_exit(process, max(0, kill_deadline - time.monotonic()))
            process.wait(timeout=5)
        except BaseException as cleanup:
            raise cleanup from failure
        raise
    if remaining:
        raise ValueError('Owned group still has live processes: ' + str(remaining))
    process.wait(timeout=5)


def owned(command):
    with tempfile.TemporaryFile(dir='/tmp/opencode' if Path('/tmp/opencode').is_dir() else None) as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            code = await_exit(process, 60)
            log.seek(0)
            data = log.read(2_097_153)
            if len(data) > 2_097_152:
                raise ValueError('Owned application log exceeds2MiB')
            output = data.decode('utf-8')
            if code != 0 or live_group(process.pid):
                raise ValueError("Owned application failed or left a live child: " + output[-6000:])
            return output
        finally:
            retire(process)


def koog(root):
    lib = root / "build/install/koog-document-agent/lib"
    probe = root / "build/libs/document-native-probes.jar"
    worker = root / "build/libs/koog-document-agent-portal-worker.jar"
    jars = [*sorted(lib.glob("*.jar")), probe, worker]
    if not jars or any(not file.is_file() for file in jars):
        raise ValueError("Build installDist/nativeProbeJar and the real generated worker before measuring")
    hashes = {file.name: digest(file) for file in jars}
    rows = []
    for _ in range(3):
        for mode in sorted(MODES - {"spring-http"}):
            classpath = str(lib / "*") + os.pathsep + str(probe)
            if mode == "outline-direct":
                classpath += os.pathsep + str(worker)
            output = owned([java(), "--enable-native-access=ALL-UNNAMED", "-cp", classpath, "demo.koog.ApplicationMeasurements", mode])
            found = [decode(line.removeprefix("PITCH_METRICS ")) for line in output.splitlines() if line.startswith("PITCH_METRICS ")]
            if len(found) != 1 or found[0]["mode"] != mode:
                raise ValueError("Missing or duplicate actual application metrics")
            found[0]["artifacts"] = hashes
            rows.extend(found)
    return rows


def snapshot(pid):
    status = Path(f"/proc/{pid}/status").read_text()
    return {"pid": pid, "rssKiB": int(re.search(r"VmRSS:\s+(\d+)", status)[1]),
            "threads": int(re.search(r"Threads:\s+(\d+)", status)[1]), "hostHeapBytes": None}


def spring(root):
    jar = root / "build/libs/application.jar"
    password = "synthetic-measurement-only"
    rows = []
    temporary_parent = "/tmp/opencode" if Path("/tmp/opencode").is_dir() else None
    for _ in range(3):
        with tempfile.TemporaryDirectory(prefix="pitch-report-measure-", dir=temporary_parent) as temporary:
            fixture = Path(temporary)
            shutil.copyfile(jar, fixture / "application.jar")
            (fixture / "templates").mkdir()
            (fixture / "templates/approved.txt").write_text("Quarterly é🙂", encoding="utf-8")
            candidate = owned([java(), "--enable-native-access=ALL-UNNAMED", "-jar", str(fixture / "application.jar"), "measure", temporary]).strip()
            document = decode(candidate)
            (fixture / "candidate.json").write_text(candidate, encoding="utf-8")
            review = {"reviewer": "explicit-synthetic-application-measurement-fixture-only",
                      "documentSha256": hashlib.sha256(candidate.encode()).hexdigest(),
                      "jarSha256": document["binding"]["implementationSha256"],
                      "resourceGeneration": document["binding"]["resourceGeneration"]}
            (fixture / "trusted-review.properties").write_text("\n".join(f"{k}={v}" for k, v in review.items()))
            with (fixture / "server.log").open("w+") as log:
                began = time.perf_counter_ns()
                command = [java(), "--enable-native-access=ALL-UNNAMED", "-Dreport.approverPassword=" + password,
                           "-Dreport.readerPassword=" + password, "-jar", str(fixture / "application.jar"), "serve", temporary, "0"]
                process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
                try:
                    deadline = time.monotonic() + 40
                    ready = None
                    while exit_status(process) is None and time.monotonic() < deadline:
                        ready = re.search(r"^REPORT_READY port=(\d+)$", (fixture / "server.log").read_text(), re.MULTILINE)
                        if ready:
                            break
                        time.sleep(0.02)
                    if not ready:
                        raise ValueError("No actual ready server: " + (fixture / "server.log").read_text()[-6000:])
                    setup = time.perf_counter_ns() - began
                    base = "http://127.0.0.1:" + ready[1]
                    authorization = "Basic " + base64.b64encode(("approver:" + password).encode()).decode()
                    ids = set()
                    def request(path, expected=200):
                        query = urllib.request.Request(base + path, headers={"Authorization": authorization})
                        try:
                            response = urllib.request.urlopen(query, timeout=10)
                        except urllib.error.HTTPError as error:
                            response = error
                        with response:
                            content = response.read(65_537).decode("utf-8")
                            if response.status != expected:
                                raise ValueError("Unexpected HTTP outcome: " + str(response.status))
                            return content, response.headers
                    def report():
                        content, headers = request("/reports/approved")
                        identity = str(uuid.UUID(headers["X-Request-Id"]))
                        if content != "Quarterly é🙂" or headers["X-Transaction-Outcome"] != "COMMITTED" or identity in ids:
                            raise ValueError("Unverified or duplicate request-specific report effect")
                        ids.add(identity)
                    for _ in range(20):
                        report()
                    before = snapshot(process.pid)
                    samples = []
                    for _ in range(100):
                        start = time.perf_counter_ns()
                        report()
                        samples.append(time.perf_counter_ns() - start)
                    after = snapshot(process.pid)
                    drain = time.perf_counter_ns()
                    process.stdin.write(b"drain\n")
                    process.stdin.flush()
                    deadline = time.monotonic() + 5
                    while time.monotonic() < deadline:
                        try:
                            request("/actuator/health/readiness", 503)
                            break
                        except ValueError:
                            time.sleep(0.02)
                    request("/actuator/health/readiness", 503)
                    request("/reports/approved", 503)
                    process.stdin.write(b"stop\n")
                    process.stdin.flush()
                    code = await_exit(process, 15)
                    output = (fixture / "server.log").read_text()
                    if code != 0 or live_group(process.pid) or "REPORT_RETIRED actualWorker=" not in output or "REPORT_AUDIT committed=120 bodyEntries=120 cleanContexts=120" not in output:
                        raise ValueError("Missing actual request effects/retirement: " + output[-6000:])
                    ordered = sorted(samples)
                    runtime_version = re.search(r"Starting ReportApplication using Java ([^ ]+)", output)
                    if not runtime_version:
                        raise ValueError("Missing actual Spring runtime version")
                    rows.append({"schema": 1, "mode": "spring-http", "pid": process.pid, "warmups": 20, "samples": samples,
                                 "java": runtime_version[1], "os": os.uname().sysname + " " + os.uname().release, "arch": os.uname().machine,
                                 "medianNs": (ordered[49] + ordered[50]) // 2, "p95Ns": ordered[94], "setupNs": setup,
                                 "drainNs": time.perf_counter_ns() - drain, "retirement": "physical", "pressure": None,
                                 "resourcesBefore": before, "resourcesAfter": after, "artifacts": {jar.name: digest(jar)},
                                 "scope": "loopback full GET/Basic evaluation auth/JDBC/new client connection; startup includes JVM launch; drain includes readiness polling/stop; heap unavailable; no overhead or HTTP throughput claim"})
                finally:
                    retire(process)
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("application", choices=["koog", "spring", "validate"])
    parser.add_argument("path", type=Path)
    args = parser.parse_args()
    if args.application == "validate":
        with args.path.open("rb") as stream:
            data = stream.read(2_097_153)
        if len(data) > 2_097_152:
            raise ValueError("Application raw input exceeds2MiB")
        validate([decode(line) for line in data.decode("utf-8").splitlines() if line])
        print("PITCH_MEASUREMENTS_VALID observations-only; publication custody and presentation-machine rehearsal remain separate")
        return
    rows = koog(args.path.resolve()) if args.application == "koog" else spring(args.path.resolve())
    for row in rows:
        row["artifacts"]["measurement-driver.py"] = digest(Path(__file__))
    validate(rows)
    for row in rows:
        print(json.dumps(row, ensure_ascii=True, separators=(",", ":")))


if __name__ == "__main__":
    main()
