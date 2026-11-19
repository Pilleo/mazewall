---
title: "Reject unterminated quoted values in recorded eBPF evidence"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: [":profiler"]
target_files:
   - "profiler/src/main/kotlin/io/mazewall/profiler/collector/EbpfEventParser.kt"
   - "profiler/src/test/kotlin/io/mazewall/profiler/collector/CollectorHybridTest.kt"
target_symbols:
   - "parseFields"
   - "parseLine"
   - "EbpfParseResult"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Reject unterminated quoted values in recorded eBPF evidence

**Context:**
Recursive state-design review C-03: the pure field scanner publishes a pathname prefix when EOF arrives before a closing quote, and malformed trailing fields preserve a successful prefix map. These become zero-drop observations and incorrect diagnostic write paths. Public recorded-log sessions independently remain incomplete; no policy bypass is established.

**Needed:**
1. Validate the complete record with a private parsed/malformed outcome before constructing observations; count malformed records once as dropped and preserve valid quoting, escaping and incomplete-conversion behavior.

## Evidence and smallest correction

[Recursive review C-03](../reviews/2026-11-05-state-complexity-review.md):
`EbpfEventParser.kt:83–93` reaches EOF without requiring a closing quote, then
publishes the prefix. The deliberately unterminated record below becomes an
ordinary zero-drop IoUring observation at `:32–35,46–51`; `BobCompiler.kt:39–43`
places its prefix in write-path evidence.

```text
kind=uring tid=1 opcode=IORING_OP_WRITE path="/tmp/prefix
```

Malformed trailing fields also preserve a successful partial map (`:76–77,100`).
Use a private pure `Parsed(fields) | Malformed` outcome, or an equally explicit
nullable rejection, and validate the whole record before constructing any
observation. Reuse existing immutable results and loss accounting. No parser
dependency, new transport format or public API change is needed.

## Acceptance and verification

- Reject unclosed quotes, dangling escapes, junk after a closing quote and
  incomplete trailing fields. Each malformed nonblank/noncomment record counts
  exactly once as dropped and emits no prefix observation.
- One valid WRITE plus one unterminated WRITE yields one observation, one drop,
  and no malformed-prefix entry in either BoB read or write paths.
- Preserve valid quoted spaces, escaped quotes/backslashes, unquoted tokens,
  ignored comments/blanks and recorded-log incomplete-conversion behavior.
- Run `CollectorHybridTest` and the owning profiler tests, then `./gradlew build`
  at implementation completion. The new regression needs no kernel operation;
  this review established source control flow, not a new executable reproduction.

## Impact and existing ownership

Public recorded-log assembly independently forces incomplete drain
(`MazewallProfiler.kt:98–106,139–169`), so this is incorrect diagnostic/BoB
evidence, **not an established policy bypass**. Keep the resolved
[quoted-whitespace implementation](resolved/issue-20260820-231247-ebpf-whitespace-preservation.md)
and [whitespace revalidation](resolved/issue-20260821-113001-preserve-whitespace-ebpf.md)
resolved. The [unknown-opcode owner](issue-20261103-235400-980-reject-unknown-io-uring-operations-as-complete-rea.md)
handles semantic classification, not malformed syntax. Active/resolved searches
found no exact owner of this residual; dependencies remain empty.

---
<!-- id: issue-20261105-182827-530-reject-unterminated-quoted-values-in-recorded-ebpf  file: issue-20261105-182827-530-reject-unterminated-quoted-values-in-recorded-ebpf.md -->
