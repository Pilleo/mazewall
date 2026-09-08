#!/usr/bin/env python3
"""
Multi-Harness PostToolUse Compiler Guard for mazewall (jseccomp).
Supports:
  - Antigravity (toolCall.args.TargetFile / FilePath)
  - Codex (tool_use / tool_call with path / file / target_file)
  - Grok (PostToolUse payload with path / file / target_file)
  - Direct CLI invocation (python3 scripts/compiler_guard.py <file>)
"""

import sys
import json
import os
import subprocess

try:
    from error_memory_capture import record_negative_example
except ImportError:
    sys.path.append(os.path.dirname(os.path.abspath(__file__)))
    from error_memory_capture import record_negative_example

MODULE_MAP = {
    "enforcer": ":enforcer",
    "platform": ":platform",
    "profiler": ":profiler",
    "portal": ":portal",
    "portal-codegen": ":portal-codegen",
    "portal-worker": ":portal-worker",
    "demos/agent-sandbox-demo": ":demos:agent-sandbox-demo",
    "demos/cli-demo": ":demos:cli-demo",
    "demos/vulnerable-web-app": ":demos:vulnerable-web-app",
    "tools/orchestrator": ":tools:orchestrator"
}

def detect_gradle_module(file_path, repo_root):
    try:
        rel_path = os.path.relpath(file_path, repo_root)
    except Exception:
        rel_path = file_path
    for prefix, module_name in MODULE_MAP.items():
        if rel_path.startswith(prefix + "/") or rel_path == prefix:
            return module_name
    return None

def extract_target_file(args, data):
    # 1. Direct CLI argument
    if len(args) > 1 and not args[1].startswith("-"):
        return args[1]

    # 2. Extract from JSON stdin
    # Antigravity structure
    tool_call = data.get("toolCall", {})
    tool_args = tool_call.get("args", {})
    if not tool_args:
        # Grok / Codex structure
        tool_args = data.get("tool_args") or data.get("args") or data.get("parameters") or {}
        if not tool_args and "tool" in data:
            tool_args = data.get("tool", {}).get("args", {})

    target = (
        tool_args.get("TargetFile")
        or tool_args.get("FilePath")
        or tool_args.get("target_file")
        or tool_args.get("path")
        or tool_args.get("file")
        or tool_args.get("file_path")
        or data.get("path")
        or data.get("file")
    )
    return target

def main():
    target_file = None
    data = {}

    if not sys.stdin.isatty():
        try:
            content = sys.stdin.read().strip()
            if content:
                data = json.loads(content)
        except Exception:
            data = {}

    target_file = extract_target_file(sys.argv, data)

    if not target_file:
        print("{}")
        sys.exit(0)

    if not (target_file.endswith(".kt") or target_file.endswith(".java") or target_file.endswith(".kts")):
        print("{}")
        sys.exit(0)

    repo_root = os.path.expanduser("~/Documents/code/java/jseccomp")
    module_target = detect_gradle_module(target_file, repo_root)

    if not module_target:
        print("{}")
        sys.exit(0)

    gradlew = os.path.join(repo_root, "gradlew")
    task = f"{module_target}:compileKotlin"
    extra_props = ["-PincludeOrchestrator=true"] if module_target == ":tools:orchestrator" else []

    try:
        proc = subprocess.run(
            [gradlew, task, "-q", "--console=plain"] + extra_props,
            cwd=repo_root,
            capture_output=True,
            text=True,
            timeout=25
        )
    except subprocess.TimeoutExpired:
        print("{}")
        sys.exit(0)
    except Exception:
        print("{}")
        sys.exit(0)

    if proc.returncode != 0:
        err_output = proc.stdout.strip() or proc.stderr.strip()
        lines = err_output.splitlines()

        base_name = os.path.basename(target_file)
        matching = [l for l in lines if base_name in l or "e: " in l or "error:" in l]

        if matching:
            diag = "\n".join(matching[:10])
        else:
            diag = "\n".join(lines[:10])

        try:
            record_negative_example(target_file, diag, module_target)
        except Exception:
            pass

        error_message = (
            f"[Compiler Guard: Kotlin compilation error in {module_target}]\n"
            f"{diag}\n"
            f"Recorded to Continuous Error Memory. Fix this syntax/type error before proceeding."
        )
        sys.stderr.write(error_message + "\n")
        sys.exit(1)

    print("{}")
    sys.exit(0)

if __name__ == "__main__":
    main()
