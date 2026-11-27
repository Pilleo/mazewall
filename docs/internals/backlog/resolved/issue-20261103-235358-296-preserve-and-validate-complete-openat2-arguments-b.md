---
title: "Preserve and validate complete openat2 arguments before supervised effects"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: [":enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorArgumentExtractor.kt"
target_symbols:
  - "SupervisorArgumentExtractor"
open_questions: false
---

# 🟡 [Severity: HIGH]: Preserve and validate complete openat2 arguments before supervised effects

**Context:**
Repository-wide review, 2026-11-04: [SUP-03](../reviews/2026-11-04-repository-review-findings.md#sup-03). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P1 · pure source-path reproduction plus source-confirmed native consequence · high confidence · supervised openat2.**

**Trigger:** an approved existing relative file and either:

1. readable `open_how` with `O_WRONLY | O_TRUNC`, but syscall `size == 0`; or
2. `size == 24`, `how.flags == (1ULL << 32) | O_WRONLY | O_TRUNC`, with zero mode/resolve.

Both original calls should be rejected before opening the file.

**Connected evidence:**

- `enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorArgumentExtractor.kt:30–40` ignores `args[3]`, reads 24 bytes, and truncates the 64-bit flags using `toInt()`.
- `enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisedOpen.kt:72–89` accepts the decoded structure and stores flags as `OpenFlags(Int)`; `SupervisorOpenPlan.kt:20–25` widens the truncated value again.
- `enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/TraceeAuthorityExecution.kt:91–97,160–175` executes the rewritten flags with a fresh 24-byte structure and fixed size. With a permitted relative regular file, its added `RESOLVE_BENEATH` does not prevent truncation.
- Official Linux v6.12 `fs/open.c:1215–1238,1449–1465` rejects unknown 64-bit flags and sizes below `OPEN_HOW_SIZE_VER0` before the open. The broker removes exactly those rejection conditions.

**Actual reproduction:** `OpenHowRepro.kt` compiles the current extractor, typed request, route-context and plan sources. No native open is made. Output:

```text
tracee size=0 flags=513 -> accepted plan flags=513, resolve=8
tracee size=24 flags=4294967809 -> accepted plan flags=513, resolve=8
Pure extraction/planning only; no native call or file effect was performed
```

**Consequence:** requests that should fail with EINVAL can instead open/truncate an approved file. Ignoring an oversized structure's extension bytes similarly drops the kernel's extension validation. This is semantic/data-integrity failure even without a path-policy escape; actual file truncation was not exercised here.

**Smallest correction:** preserve the 64-bit `open_how.flags` until kernel validation, and validate/copy according to the caller's size before authorizing any effect. Honor the supported ABI's size/extension rules, or explicitly fail closed on unsupported sizes. Do not silently narrow an untrusted flags word.

**Regression:** extraction-to-native-boundary tests for size 0/23/24, overlarge and extended structures (zero/nonzero tails), and flags above bit 31. Assert invalid requests cannot reach an effectful open. A later host-safe native control should compare direct and brokered errno and verify an owned sentinel file retains its contents; include a valid read/open positive control.

**Mapping/dependency:** **expanded residual** of `docs/internals/backlog/resolved/issue-20260821-113000-decode-openat2-open-how.md`. The original pointer-as-flags bug is fixed, but complete structure validation is not. Plan B4 explicitly requires rejecting unknown/mutation flags before effects; the roadmap's first live-scoping envelope explicitly excludes intercepted `openat2`, so do not present this as a defect in an already implemented live-scoping release.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

## Resolution Evidence (2026-11-27)
- `SupervisorArgumentExtractor` now uses `openat2` size. Sizes below 24 are `EINVAL`. Sizes above 4096 and nonzero extension tails are `E2BIG`. Flag bits above 31 are `EINVAL` and are not narrowed into `OpenFlags`. A zero tail is accepted. `SupervisorInjectionPlan` turns that errno into `Deny` before an open plan exists.
- Regression: `OpenAt2HowValidationTest` covers sizes 0, 23, 24, 32 with a zero and nonzero tail, 4097, and a high flag bit, plus a valid size-24 read. No native open was run. Direct-versus-brokered errno and sentinel-file retention remain a later host check.

---
<!-- id: issue-20261103-235358-296-preserve-and-validate-complete-openat2-arguments-b  file: issue-20261103-235358-296-preserve-and-validate-complete-openat2-arguments-b.md -->
