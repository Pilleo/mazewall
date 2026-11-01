---
title: "Copy read-only released module source into writable distribution staging"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/hotspot-agentless/internal/tooling/distribution.go"
  - "experiments/hotspot-agentless/internal/tooling/distribution.go"
  - "profiler/hotspot-agentless/internal/tooling/source_copy_test.go"
  - "experiments/hotspot-agentless/internal/tooling/source_copy_test.go"
target_symbols:
  - "copySourceTree"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Copy read-only released module source into writable distribution staging

**Context:**
Released SDK v0.2.0 source selection exposes an actual host copy failure: nested Go module-cache directories are 0555, so copying their children into a destination with identical directory modes fails with permission denied. Both physical collector owners are affected; source cache must remain untouched.

**Needed:**
1. Keep temporary destination directories owner-writable while preserving file modes and source bytes; prove RED/GREEN on nested read-only source and actual selected released SDK notice positive/remove-all negative controls. Keep issue open pending independent review and final configured gate; no native distribution qualification is implied.

**Host evidence / pending review:**
Both owners' permanent `TestCopySourceTreeReadOnlyCachePreservesFiles` first
failed with permission denied creating `copy/cache/LICENSE` beneath a 0555
directory. The shared helper now retains source directory permission bits plus
owner access on temporary staging directories. The archive emits file entries,
not directory metadata; owner write access is also required for staging cleanup.
File bytes and modes (0444 notice, 0555 executable, 0640 ordinary file), nested
source directory modes, and source bytes remain unchanged. Generated-directory
exclusions and symlink/FIFO rejection remain covered.

Focused race tests now pass in both owners, including the actual selected
released SDKv0.2.0 notice test's positive copy and remove-all-notices rejection.
RED/GREEN logs and released-source identities are retained under
`/tmp/opencode/hotspot-released-sdk-pins-e144tbpe/` (`*-copy-red.log`,
`*-copy-green.log`, `selected-source-verification.json`, `impl-report.md`).
The earlier pin-only host result was 190 pass / 1 fail / 0 skips per owner;
the failure is retained as historical evidence, not relabeled green.
After the copy fix, all six host-safe packages pass in each physical owner:
195 test/subtest passes, 0 failures, 0 skips, using the actual prepared native
graph and downloaded released SDK with `GOWORK=off`. Collector commit
`60ba62318fd222b1ebf6d5c54507a7064a628044` contains only the owned collector
pins, copy fix, regression tests and README; this backlog record awaits the
controller's separate documentation restack.

This issue stays **open** pending independent scoped review and the controller's
configured final source gate. No matching rebuilt collector/bundle was available;
strict released-distribution acceptance and BPF/JVM/native qualification remain
blocked/separate. The static embedded-version comparison concern is unverified,
not a demonstrated finding and not part of this issue or fix.

---
<!-- id: issue-20261019-210638-043-copy-read-only-released-module-source-into-writabl  file: issue-20261019-210638-043-copy-read-only-released-module-source-into-writabl.md -->
