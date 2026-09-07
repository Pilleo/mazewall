---
name: file_structure
description: >
  Outline a source file before reading it. Use before inspecting Kotlin/Java/TS
  or asking what a class contains. Trigger on: file structure, outline, what is
  in this file, methods in X.
---

# File structure

Do not read a whole source file first.

1. `./scripts/adkw slice <file-or-class> --json`
2. If the envelope is `PARTIAL` or `UNAVAILABLE`, say so. Do not treat lexical fallback as parser-verified.
3. For one symbol: `./scripts/adkw symbol-source <file> <symbol> --json`
4. If Codanna is missing, continue with ADK evidence and record that impact data is incomplete.

Optional: `codanna retrieve describe <Symbol>` after `./scripts/adkw doctor` reports `READY`.
