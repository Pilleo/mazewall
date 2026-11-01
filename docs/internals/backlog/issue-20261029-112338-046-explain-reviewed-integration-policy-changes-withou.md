---
title: "Explain reviewed integration policy changes without automatic approval"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: []
target_files:
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tools/PolicyDiff.kt"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Explain reviewed integration policy changes without automatic approval

**Context:**
Approved pitch Task6: bounded2MiB operation-document comparison using existing canonical typed policies and coverage/binding model, report runtime/mode/syscall/argument/filesystem/artifact/resource/coverage/exception changes without universal safer/equivalent claims. Development-only profile same named read workload, incomplete evidence refuses conversion, explicit review remains mandatory.

**Needed:**
1. Read profiler instructions/designs, add literal diff/input-bound tests RED->GREEN, implement standard-library CLI and development example using existing profiler, preserve host/body/review boundaries; verify profiler tests and full gates.

## Outcome

- PolicyDiff compares existing canonical types, rejects malformed/oversized input,
  and reports changes without approval or universal safety/equivalence claims.
- Development profiling selects the exact reader member as CALLABLE, not KOOG_TOOL;
  incomplete coverage refuses conversion. Separate review precedes enforcement.
- Tooling stays outside the published profiler API; no dependency was added.
- Host/CLI, reviewed-enforcement, retirement and full-build checks passed. See
  [shared qualification](issue-20261029-112338-058-qualify-copied-pitch-consumers-measurements-and-ho.md).
- Additional opt-in `detektTooling` reported86 preexisting unrelated findings;
  those were not suppressed or represented as a passing opt-in check.

Open pending managed landing. Local profiling does not qualify real-data approval,
remote release provenance or future-path coverage.

---
<!-- id: issue-20261029-112338-046-explain-reviewed-integration-policy-changes-withou  file: issue-20261029-112338-046-explain-reviewed-integration-policy-changes-withou.md -->
