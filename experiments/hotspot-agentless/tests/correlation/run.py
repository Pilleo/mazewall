#!/usr/bin/env python3
"""Run the actual prepared dispatcher, plus the old-clock negative control.

Usage: python3 -B tests/correlation/run.py PREPARED_PROFILER NEW_OUTPUT
Only output is written; prepared dependencies and historical evidence are read-only.
"""
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import time


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def run(argv, cwd, output, name, expected=0):
    receipt = {"argv": list(map(str, argv)), "cwd": str(cwd), "start_ns": time.time_ns()}
    result = subprocess.run(argv, cwd=cwd, capture_output=True, timeout=120)
    (output / (name + ".stdout")).write_bytes(result.stdout)
    (output / (name + ".stderr")).write_bytes(result.stderr)
    receipt.update(exit=result.returncode, end_ns=time.time_ns(),
                   stdout_sha256=sha(output / (name + ".stdout")),
                   stderr_sha256=sha(output / (name + ".stderr")))
    (output / (name + ".json")).write_text(json.dumps(receipt, indent=2) + "\n")
    if result.returncode != expected:
        raise RuntimeError(f"{name}: exit {result.returncode}, expected {expected}: {result.stderr.decode()}")
    return result.stdout


def main():
    provider, output = map(lambda s: Path(s).resolve(), sys.argv[1:])
    fixture = Path(__file__).resolve().parent / "dispatcher.c"
    include = provider / "support/ebpf"
    output.mkdir(parents=True, exist_ok=False)
    flags = ["-O2", "-std=gnu11", "-Wall", "-Wextra", "-Werror",
             "-Wno-unused-function", "-Wno-unused-parameter", "-Wno-sign-compare",
             "-fno-strict-aliasing", "-I" + str(include)]
    cc = os.environ.get("CC", "gcc")
    deps = run([cc, *flags, "-M", str(fixture)], output, output, "selection").decode()
    headers = shlex.split(deps.replace("\\\n", " ").split(":", 1)[1])
    inputs = {str(Path(p).resolve()): sha(p) for p in headers}
    inputs[str(Path(__file__).resolve())] = sha(__file__)
    tool = run([cc, "-print-prog-name=cc1"], output, output, "compiler").decode().strip()
    inputs[tool] = sha(tool)
    (output / "inputs-before-build.json").write_text(json.dumps(inputs, indent=2) + "\n")
    binary = output / "dispatcher"
    run([cc, *flags, str(fixture), "-o", str(binary)], output, output, "build")
    (output / "binary-before-execution.json").write_text(json.dumps({str(binary): sha(binary)}, indent=2) + "\n")
    for case in ["unique", "edges"]:
        print(run([str(binary), case], output, output, case).decode(), end="")
    # Mutate the real miss assignment, retaining all dispatcher/helper boundaries.
    source = (include / "interpreter_dispatcher.ebpf.c").read_text()
    needle = "u64 correlation_id = next_correlation_id();"
    assert source.count(needle) == 1
    mutant = output / "interpreter_dispatcher.ebpf.c"
    mutant.write_text(source.replace(needle, "u64 correlation_id = bpf_ktime_get_ns();"))
    mutant_inputs = dict(inputs)
    mutant_inputs[str(mutant)] = sha(mutant)
    (output / "mutant-inputs-before-build.json").write_text(json.dumps(mutant_inputs, indent=2) + "\n")
    mutant_binary = output / "clock-mutant"
    run([cc, "-I" + str(output), *flags, str(fixture), "-o", str(mutant_binary)], output, output, "mutant-build")
    (output / "mutant-before-execution.json").write_text(json.dumps({str(mutant_binary): sha(mutant_binary)}, indent=2) + "\n")
    run([str(mutant_binary), "unique"], output, output, "clock-mutant", expected=1)
    print("old-clock actual-C mutant: expected uniqueness RED exit 1")


if __name__ == "__main__":
    main()
