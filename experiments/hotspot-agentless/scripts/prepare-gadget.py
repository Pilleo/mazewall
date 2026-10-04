#!/usr/bin/env python3
"""Delegate dependency preparation to the sole canonical collector tree."""
from pathlib import Path
import shutil
import subprocess
import sys

root = Path(__file__).resolve().parent.parent
canonical = root.parents[1] / "profiler/hotspot-agentless"
subprocess.run([sys.executable, str(canonical / "scripts/prepare-gadget.py"), *sys.argv[1:]], check=True)
name = "identity-test" if "--identity-observations" in sys.argv else ("recovery-test" if "--drop-first-definition" in sys.argv else "experiment")
root.joinpath("build").mkdir(exist_ok=True)
module = canonical / "build" / (name + ".go.mod")
# Go evaluates local replacements relative to the invoking module root.
(root / "build" / module.name).write_text(module.read_text().replace("./build/deps/", "../../profiler/hotspot-agentless/build/deps/"))
shutil.copyfile(module.with_suffix(".sum"), root / "build" / (name + ".go.sum"))
manifest = "patch-manifest.json" if name == "experiment" else name + "-manifest.json"
shutil.copyfile(canonical / "build" / manifest, root / "build" / manifest)
