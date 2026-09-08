#!/usr/bin/env python3
import sys
import json
import urllib.request

def main():
    try:
        data = json.load(sys.stdin)
    except Exception:
        data = {}

    ephemeral_hints = [
        "Local Qwen 3.5 4B (32k context, CUDA) is active via Ollama.",
        "Agent memory MCP is connected."
    ]

    output = {
        "injectSteps": [
            {
                "ephemeralMessage": " ".join(ephemeral_hints)
            }
        ]
    }
    print(json.dumps(output))

if __name__ == "__main__":
    main()
