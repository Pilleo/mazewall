#!/usr/bin/env python3
"""Stop / UserPromptSubmit memory capture for Grok, Codex, and Antigravity.

Always exit 0 and write nothing to stdout. Grok Stop is a blocking gate: any
stdout is parsed as a stop decision, and a crash/timeout is shown as a failed
hook even though it fails open.
"""
import hashlib
import json
import os
import re
import sys
import time

MEMORY_FILE = os.path.expanduser("~/.agentmemory/standalone.json")

NEGATIVE_REACTION_PATTERNS = [
    r"\bwrong\b",
    r"\bdon't\b",
    r"\bdo not\b",
    r"\bnever\b",
    r"\brevert\b",
    r"\bwhy did you\b",
    r"\bnot what i asked\b",
    r"\bundo\b",
]


def record_negative_feedback(user_feedback: str) -> None:
    if not os.path.exists(MEMORY_FILE):
        return
    with open(MEMORY_FILE, "r", encoding="utf-8") as handle:
        store = json.load(handle)
    memories = store.setdefault("mem:memories", {})
    err_hash = hashlib.sha256(user_feedback.encode("utf-8")).hexdigest()[:12]
    mem_id = f"mem_feedback_{err_hash}"
    clean_snippet = user_feedback.strip().replace("\n", " ")[:140]
    iso_time = time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime())
    memories[mem_id] = {
        "id": mem_id,
        "type": "anti-pattern",
        "title": f"Operator Negative Feedback: {clean_snippet}"[:100],
        "content": (
            "OPERATOR NEGATIVE REACTION / CORRECTION:\n"
            f"Feedback: {user_feedback}\n"
            "Context: Prior action was corrected or rejected by operator.\n"
            "Rule: Always adhere strictly to this operator preference/constraint."
        ),
        "concepts": ["operator-feedback", "anti-pattern", "negative-constraint"],
        "files": [],
        "createdAt": iso_time,
        "updatedAt": iso_time,
        "strength": 10,
        "version": 1,
        "isLatest": True,
        "sessionIds": [],
    }
    tmp = MEMORY_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as handle:
        json.dump(store, handle, indent=2)
    os.replace(tmp, MEMORY_FILE)


def _string_fields(data: dict, keys: tuple[str, ...]) -> str:
    for key in keys:
        value = data.get(key)
        if isinstance(value, str) and value.strip():
            return value
    return ""


def user_text_from_transcript(path: str) -> str:
    if not path or not os.path.exists(path):
        return ""
    steps = []
    with open(path, "r", encoding="utf-8") as handle:
        for line in handle:
            if line.strip():
                steps.append(json.loads(line))
    user_inputs = [step for step in steps if step.get("type") in ("USER_INPUT", "userMessage")]
    if not user_inputs:
        return ""
    content = user_inputs[-1].get("content", "")
    return content if isinstance(content, str) else ""


def user_text_from_payload(data: dict) -> str:
    direct = _string_fields(
        data,
        ("lastUserMessage", "userMessage", "userPrompt", "prompt", "content"),
    )
    if direct:
        return direct
    for nested_key in ("hookSpecificOutput", "toolInput", "tool_input"):
        nested = data.get(nested_key)
        if isinstance(nested, dict):
            nested_text = _string_fields(
                nested,
                ("lastUserMessage", "userMessage", "userPrompt", "prompt", "content"),
            )
            if nested_text:
                return nested_text
    transcript = data.get("transcriptPath") or data.get("transcript_path")
    if isinstance(transcript, str):
        return user_text_from_transcript(transcript)
    return ""


def should_record(text: str) -> bool:
    lower = text.lower()
    matched = [pattern for pattern in NEGATIVE_REACTION_PATTERNS if re.search(pattern, lower)]
    return len(matched) >= 2 or any(
        phrase in lower for phrase in ("not what i asked", "don't do that", "stop doing", "revert")
    )


def main() -> int:
    try:
        data = json.load(sys.stdin)
    except Exception:
        data = {}
    if not isinstance(data, dict):
        data = {}
    try:
        text = user_text_from_payload(data)
        if text and should_record(text):
            record_negative_feedback(text[:300])
    except Exception as error:
        sys.stderr.write(f"auto_memory_capture skipped: {error}\n")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SystemExit:
        raise SystemExit(0)
    except Exception as error:
        sys.stderr.write(f"auto_memory_capture failed: {error}\n")
        raise SystemExit(0)
