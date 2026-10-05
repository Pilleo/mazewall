#!/usr/bin/env python3
"""Verify actual release ELF baseline/candidate files; requires Go and readelf."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def verify(baseline, candidate):
    baseline, candidate = Path(baseline), Path(candidate)
    sections = subprocess.check_output(["readelf", "-SW", str(candidate)], text=True)
    for name in (".debug_info", ".debug_line", ".symtab"):
        if name in sections:
            raise ValueError("release retains debug/symbol section: " + name)
    if candidate.stat().st_size >= baseline.stat().st_size:
        raise ValueError("candidate has no collector byte reduction")
    info = subprocess.check_output(["go", "version", "-m", str(candidate)], text=True)
    required = (
        "io.mazewall/experiments/hotspot-agentless/cmd/mw-hotspot-collector",
        "CGO_ENABLED=0", "GOARCH=amd64", "GOOS=linux", "-trimpath=true",
    )
    for value in required:
        if value not in info:
            raise ValueError("missing release provenance: " + value)
    # Go -trimpath omits linker flags from build info. Check the linked patch
    # value here; the public native matrix separately validates the actual provider.
    patch = b"e908bb2689936481f8a696dd2347457cc44b6344c1fa78ca65b37772032703eb"
    if patch not in candidate.read_bytes():
        raise ValueError("missing linked patch identity")
    headers = subprocess.check_output(["readelf", "-lW", str(candidate)], text=True)
    if "INTERP" in headers:
        raise ValueError("collector is no longer static")
    with candidate.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    return {"baseline_bytes": baseline.stat().st_size, "candidate_bytes": candidate.stat().st_size,
            "candidate_sha256": digest, "build_info": info}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline")
    parser.add_argument("candidate")
    print(json.dumps(verify(**vars(parser.parse_args())), indent=2))
