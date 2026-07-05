---
title: "🔴 [Severity: DX-FRICTION]: Missing Extensibility in Exception Message Parsing"
severity: "HIGH"
status: "open"
priority: 0
dependencies: []
component: "enforcer"
effort: "small"
---

# 🔴 [Severity: DX-FRICTION]: Missing Extensibility in Exception Message Parsing

*   **Dimension:** DX & API Ergonomics
*   **Target Area:** `io.mazewall.profiler.iterative.IterativeProfiler` and `io.mazewall.enforcer.ContainmentViolationDetector`
*   **Failure Hypothesis:** Different JVM languages, native wrappers, or custom `FileSystemProvider` implementations might throw exceptions containing localized error strings or unusual formatting when access is denied. The `DENIED_PHRASES` list in `ContainmentViolationDetector` is hardcoded.
*   **Context & Proof:** `ContainmentViolationDetector` uses a fixed `arrayOf` strings (e.g., `"Operation not permitted"`, `"refusé"`). The `IterativeProfiler` uses this exact array to identify exception boundaries. If a user's framework throws a custom wrapper containing "Blocked by sandbox", the violation is completely ignored.
*   **Cascading Risk Potential:** DX friction. Users in non-standard environments or using custom filesystem providers cannot use the Iterative Profiler.
*   **Recommendation:** Provide a public configuration hook in `IterativeProfiler` or `Policy` allowing developers to supply custom regexes or phrases for violation detection.
