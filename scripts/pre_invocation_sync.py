#!/usr/bin/env python3
import sys
import json
import subprocess
import os

MEMORY_FILE = os.path.expanduser("~/.agentmemory/standalone.json")

def get_recent_anti_patterns():
    if not os.path.exists(MEMORY_FILE):
        return []
    try:
        with open(MEMORY_FILE, "r") as f:
            data = json.load(f)
        mems = data.get("mem:memories", {})
        anti_patterns = []
        for m in mems.values():
            if m.get("type") == "anti-pattern":
                anti_patterns.append(m.get("title", ""))
        return anti_patterns[-3:] # Last 3 negative examples
    except Exception:
        return []

def main():
    try:
        data = json.load(sys.stdin)
    except Exception:
        data = {}

    # Quick incremental index check via code_atlas wrapper
    script_path = os.path.expanduser("~/Documents/code/java/jseccomp/scripts/code_atlas.sh")
    if os.path.exists(script_path):
        subprocess.run([script_path, "describe", "Policy"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    anti_patterns = get_recent_anti_patterns()
    anti_msg = ""
    if anti_patterns:
        anti_msg = " [Active Error Memory: Avoid " + "; ".join(anti_patterns) + "]"

    output = {
        "injectSteps": [
            {
                "ephemeralMessage": f"Workspace Code Index (Codanna) is synced. BGE-M3 semantic memory active.{anti_msg}"
            }
        ]
    }
    print(json.dumps(output))

if __name__ == "__main__":
    main()
