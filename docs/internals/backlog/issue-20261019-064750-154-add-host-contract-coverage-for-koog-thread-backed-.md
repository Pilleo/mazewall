---
title: "Add host contract coverage for Koog thread-backed tools"
severity: "LOW"
status: "open"
priority: low
dependencies: []
component: "testing"
target_modules: []
target_files:
   - "integrations/koog/src/test/kotlin/io/mazewall/koog/ThreadToolAdapterTest.kt"
   - "src/sharedTest/kotlin/io/mazewall/testing/MockManagedRuntime.kt"
   - "integrations/koog/README.md"
target_symbols:
   - "ThreadToolAdapter"
needs_kernel: false
core_lock: false
effort: "small"
autonomy: "supervised"
open_questions: false
has_side_effects: false
---

# 🟡 [Severity: LOW]: Add host contract coverage for Koog thread-backed tools

**Context:**
The thread adapter has real native published-consumer coverage and await-helper host tests, but no dedicated adapter host suite. Add fast contracts against the real ManagedRuntime and domain using existing repository-only native doubles. Do not add a production submitter seam, reflection-based construction, runtime policy changes or dependencies.

**Needed:**
1. Add a repository-only owned-runtime fixture that reuses MockNativeEngine and MockPlatformProvider and restores test state after bounded drain/close.
2. Exercise real Koog registry binding, argument/result validation placement, once-only action dispatch, wrong-owner rejection, cancellation/drain lifetime and closed-owner rejection.
3. Prove representative guard removals fail the tests; run focused host suites and preserve independent native qualification without claiming mocked tests exercise kernel enforcement.

## Verified local implementation

Eight new adapter contracts use the actual runtime/domain and upstream registry,
with native installation mocked only through an internal shared-test fixture.
The complete Koog host suite passes23 tests, no failures/errors/skips; typed
Detekt and formatting pass. Argument/result/cancellation/original-caller-hop
mutations each fail their intended contract. Review found and corrected two test
failure-path defects; repeated cancellation/excess-admission probes now fail
boundedly and still establish complete retirement in `finally`.

Twenty repeated runs of this eight-test class pass160 cases with zero owned
manager/domain threads remaining after each run. These are mocked-installation
host contracts, not kernel-denial or published-consumer evidence. Existing
production adapter/helper source bytes were unchanged at that test checkpoint
after mutation restoration. The later private await-helper refactor belongs to
the separate analyzer qualification scope, not this fixture-only correction.

Evidence: `.superpowers/sdd/2026-10-19-koog-thread-host-contracts/` and
`.superpowers/sdd/2026-10-19-thread-contract-stress/koog/`. Local implementation
review has no outstanding code findings; keep this item open for scoped
integration. The later analyzer correction and full optional build are now green;
see `.superpowers/sdd/2026-10-19-strict-analyzer-repair/completion-receipt.json`.

---
<!-- id: issue-20261019-064750-154-add-host-contract-coverage-for-koog-thread-backed-  file: issue-20261019-064750-154-add-host-contract-coverage-for-koog-thread-backed-.md -->
