#!/usr/bin/env python3
"""
Antigravity Stop Hook Handler:
When an agent execution terminates (terminationReason == 'model_stop'),
this hook:
1. Checks transcript for negative operator feedback / rejection / corrections
2. If negative reaction is detected, records an immediate high-priority anti-pattern into agentmemory
3. Synthesizes any positive durable lessons learned
"""
import sys
import json
import os
import time
import hashlib
import re

MEMORY_FILE = os.path.expanduser("~/.agentmemory/standalone.json")

NEGATIVE_REACTION_PATTERNS = [
    r"\bno\b", r"\bwrong\b", r"\bdon't\b", r"\bdo not\b", r"\bstop\b",
    r"\bnever\b", r"\bbad\b", r"\bbroken\b", r"\bfailed\b", r"\berror\b",
    r"\brevert\b", r"\bwhy did you\b", r"\bnot what i asked\b", r"\bundo\b"
]

def record_negative_feedback(user_feedback, last_assistant_action=""):
    if not os.path.exists(MEMORY_FILE):
        return
    try:
        with open(MEMORY_FILE, "r") as f:
            store = json.load(f)
        memories = store.setdefault("mem:memories", {})

        err_hash = hashlib.sha256(user_feedback.encode("utf-8")).hexdigest()[:12]
        mem_id = f"mem_feedback_{err_hash}"

        clean_snippet = user_feedback.strip().replace("\n", " ")[:140]
        title = f"Operator Negative Feedback: {clean_snippet}"[:100]

        iso_time = time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime())
        memories[mem_id] = {
            "id": mem_id,
            "type": "anti-pattern",
            "title": title,
            "content": (
                f"OPERATOR NEGATIVE REACTION / CORRECTION:\n"
                f"Feedback: {user_feedback}\n"
                f"Context: Prior action was corrected or rejected by operator.\n"
                f"Rule: Always adhere strictly to this operator preference/constraint."
            ),
            "concepts": ["operator-feedback", "anti-pattern", "negative-constraint"],
            "files": [],
            "createdAt": iso_time,
            "updatedAt": iso_time,
            "strength": 10,
            "version": 1,
            "isLatest": True,
            "sessionIds": []
        }

        with open(MEMORY_FILE, "w") as f:
            json.dump(store, f, indent=2)

    except Exception:
        pass

def main():
    try:
        input_data = json.load(sys.stdin)
    except Exception:
        input_data = {}

    termination_reason = input_data.get("terminationReason", "")
    transcript_path = input_data.get("transcriptPath", "")

    if termination_reason == "model_stop" and transcript_path and os.path.exists(transcript_path):
        try:
            steps = []
            with open(transcript_path, "r") as f:
                for line in f:
                    if line.strip():
                        steps.append(json.loads(line))

            # Inspect user inputs across the transcript for negative feedback/rejections
            user_inputs = [s for s in steps if s.get("type") in ("USER_INPUT", "userMessage")]
            if user_inputs:
                latest_user = user_inputs[-1]
                content = latest_user.get("content", "")
                if isinstance(content, str):
                    lower_content = content.lower()
                    # Check if user expressed explicit negative reaction/correction
                    matched = [p for p in NEGATIVE_REACTION_PATTERNS if re.search(p, lower_content)]
                    if len(matched) >= 2 or any(p in lower_content for p in ["not what i asked", "don't do that", "stop doing", "revert"]):
                        record_negative_feedback(content[:300])

        except Exception:
            pass

    # Codex Stop hooks are observers.  They must not write a decision object to
    # stdout: any output there is parsed as a Stop-hook control response.

if __name__ == "__main__":
    main()
