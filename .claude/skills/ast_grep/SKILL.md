---
name: ast_grep
description: >
  Syntax-aware search with ast-grep. Use for structural find/replace, not text grep.
---

# ast-grep

Prefer `ast-grep` on PATH. If it is missing, say so and use `./scripts/adkw slice` / `symbol-source` instead.

```bash
ast-grep run --lang kotlin --pattern 'fun $NAME($$$)' src/
ast-grep run --lang kotlin --kind function_declaration --json=compact --stdin < file.kt
```

Always pass `--lang` and a path. Confirm replacements with tests. Do not treat search hits as verified ranges; verify with ADK `symbol-source`.
