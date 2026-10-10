---
name: file_structure
description: >
  Outline a large or unfamiliar source file when a structural map reduces reading.
  Use when asked for file structure, an outline, or methods in a class.
---

# File structure

For short or already-understood files, use a direct read or exact symbol search. For large or unfamiliar files:

1. `./scripts/adkw slice <file-or-class> --json`
2. If the envelope is `PARTIAL` or `UNAVAILABLE`, say so. Do not treat lexical fallback as parser-verified.
3. For one symbol: `./scripts/adkw symbol-source <file> <symbol> --json`
4. If Codanna is missing, continue with ADK evidence and record that impact data is incomplete.

Optional: `codanna retrieve describe <Symbol>` when the Codanna index is ready.
