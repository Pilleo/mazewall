---
title: "`IterativeProfiler` Path Truncation on Spaces"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: HIGH]: `IterativeProfiler` Path Truncation on Spaces

*   **Dimension:** Cascading Failure Analysis
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeProfiler.kt` (specifically `findPathEnd`)
*   **Failure Hypothesis:** When a profiled workload is denied access to a file whose absolute path contains spaces (e.g. `/var/log/my file.txt`), the `IterativeProfiler` incorrectly truncates the path at the first whitespace when parsing the exception message, returning an invalid path and failing to whitelist the correct resource.
*   **Context & Proof:** In `IterativeProfiler.findPathEnd`, the backwards scan loop continues while `end >= 0 && (msg[end].isWhitespace() || msg[end] == '(')`. This strips trailing spaces. Then, `resolveAbsolutePath` scans backwards until it hits `!msg[start - 1].isWhitespace()`. This means that any spaces *within* the path itself will act as boundary markers, prematurely ending the path resolution. The profiler then attempts to whitelist the truncated snippet, leaving the actual file blocked.
*   **Cascading Risk Potential:** High stability and usability bug. Completely breaks iterative profiling for any workload executing in directories containing spaces.
*   **Recommendation:** Stop relying on naive string-message parsing for `IOException` or fallback exception wrappers. If exceptions must be parsed, consider injecting specific delimiters around the path string in the enforcer exception message, or using regex boundary matching that accounts for quoted/spaced paths.

## Secondary Logic Bugs, Optimizations & Enhancements
