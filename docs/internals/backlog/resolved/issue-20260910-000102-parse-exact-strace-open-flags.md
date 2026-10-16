---
title: "Parse exact strace open flag operands"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files: ["profiler/src/main/kotlin/io/mazewall/profiler/compiler/StraceLogParser.kt"]
target_symbols: ["StraceLogParser", "OpenFlagsEvidence"]
open_questions: false
---

# Parse exact strace open flag operands

**Context:** Substring scans could mistake hostile path text for flags and silently reduced unknown
symbolic flags to zero.

**Needed:** Tokenize quotes, escapes, and nested structures; inspect only the syscall's flags operand.

**Resolution:** The parser now tokenizes top-level operands, decodes strace escapes, handles openat2's
`flags=` member, preserves numeric bits, and makes unknown symbols explicit incomplete evidence.
Octal path escapes are decoded together with the declared filesystem charset; truncated or
undecodable quoted operands publish failed path evidence rather than an apparently valid prefix.

---
<!-- id: issue-20260910-000102-parse-exact-strace-open-flags  file: issue-20260910-000102-parse-exact-strace-open-flags.md -->
