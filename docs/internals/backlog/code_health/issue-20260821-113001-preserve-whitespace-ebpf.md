---
title: "Preserve whitespace in recorded eBPF field values"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/collector/EbpfEventParser.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3796525664
---

# 🟡 [Severity: MEDIUM]: Preserve whitespace in recorded eBPF field values

**Context:** Recorded eBPF events are tokenized on every whitespace boundary with no quoting or escaping support, so a valid path such as `path=/tmp/My File` is compiled as `/tmp/My` while the remaining token is discarded. Profiles for files or endpoints containing spaces consequently produce incorrect Bills of Behavior and enforcement paths.

**Problem:**
- Tokenization on whitespace boundary
- No quoting or escaping support
- Paths with spaces truncated
- Incorrect Bill of Behavior

**Impact:**
- Files/endpoints with spaces produce incorrect profiles
- Enforcement paths wrong

**Needed:**
1. Define and parse escaped or length-delimited event format
2. Support quoted values in eBPF events
3. Preserve paths with spaces

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3796525664
