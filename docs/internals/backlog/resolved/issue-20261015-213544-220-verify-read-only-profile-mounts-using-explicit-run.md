---
title: "Verify read-only profile mounts using explicit runtime inspect flags"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "scripts/run_vulnerable_app_demo.sh"
  - "exploits/test_web_demo.py"
target_symbols: ["start_protected"]
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Verify read-only profile mounts using explicit runtime inspect flags

**Context:**
Exact CI38807405275 at4d7d84dd passes host, kernel and mutation gates then
rejects the protected mount because the guard treats Mode as read-only evidence.
Podman's official InspectMount definition documents Mode as SELinux labels,
Options as mount options and RW as the read-write boolean. Mode is not portable
read-only evidence. Require the exact profile destination and explicit RW=false;
missing, string, numeric or true values cannot authorize scenarios.

**Needed:**
1. Correct the real-inspect fixture to include typed RW metadata, reproduce Podman acceptance and writable/missing/ill-typed rejection failures, then change only the guard's evidence field. Retain all identity, profile and retirement checks.

## Established evidence

CI failure is `protected profile mount is not read-only`
(`run-293541e147e8`); no core correctness/mutation stage failed. Official Podman
source `libpod/define/container_inspect.go` defines RW as the read-write boolean
and Mode as SELinux options. Real script regressions RED `run-10618e36942e`:
valid Podman Mode=z/RW=false rejected, but Mode=ro with true, missing, string
false or numeric0 RW accepted. The revised guard uses identity `is False`, not
truthiness or an absent-field fallback.

## Resolution

All64 report/ownership tests pass (`run-2b84520347c7`,303s), including actual
script acceptance of the Podman SELinux-mode shape and rejection before scenarios
for writable, missing, string-false, numeric-zero and wrong-destination metadata.
The real installed Docker five-row comparison and exact retirement pass
(`run-34fff32d25ce`,49s). Full included-module build passes
(`run-f2ae644a6129`,331487ms); shell syntax and whitespace checks pass.

The connected review confirms a one-field correction against the official
Podman InspectMount contract: no Mode/options inference, boolean coercion,
permission fallback, altered Compose mount, identity check or disabled gate.
Actual Podman execution remains the next exact-head remote CI checkpoint; local
fixture acceptance is not represented as a local Podman runtime run.

---
<!-- id: issue-20261015-213544-220-verify-read-only-profile-mounts-using-explicit-run  file: issue-20261015-213544-220-verify-read-only-profile-mounts-using-explicit-run.md -->
