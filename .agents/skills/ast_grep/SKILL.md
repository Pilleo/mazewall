---
name: ast_grep
description: >
  Perform structural, syntax-aware search and replace on source code using ast-grep.
  Use when searching for code patterns ignoring comments, spacing, and styling,
  or when performing systematic refactors.
  Trigger on: "ast-grep", "structural search", "search pattern in code", "structural replace", "systematic refactoring".
---

# Skill: Structural Search & Refactoring (ast-grep)

Use ast-grep (via the repository wrapper `./scripts/sg.sh`) when you need to perform context-aware code searches or refactoring. Unlike raw regex or grep, ast-grep understands the programming language syntax and ignores comments, layout spacing, and formatting.

## Usage Invariants

*   **Always specify target language**: Use `-l kotlin` or `--lang kotlin` for Kotlin files.
*   **Always specify scope**: Provide a path limit (e.g., `enforcer/src/`) or a specific file name to avoid traversing unrelated files.

## Common Code Search Patterns

1. **Find all methods on a class/object**:
   ```bash
   ./scripts/sg.sh run --pattern 'fun $METHOD($$$)' --lang kotlin enforcer/src/
   ```

2. **Find error handling swallows (violates Fail-Closed invariant)**:
   ```bash
   ./scripts/sg.sh run --pattern 'try { $$$ } catch ($E: Exception) { }' --lang kotlin enforcer/src/
   ```

3. **Find specific system call number registration**:
   ```bash
   ./scripts/sg.sh run --pattern 'Syscall.$SYS.numberFor($ARCH)' --lang kotlin
   ```

## Structural Refactoring / Replacement

To automatically modify the codebase structure:

```bash
./scripts/sg.sh run \
  --pattern 'oldMethodCall($A, $B)' \
  --rewrite 'newMethodCall(first = $A, second = $B)' \
  --lang kotlin \
  --update-all \
  enforcer/src/
```

- Use `--update-all` to apply changes automatically without prompt blocks.
- Verify the changes compile and run by executing `./gradlew test` immediately afterwards.
