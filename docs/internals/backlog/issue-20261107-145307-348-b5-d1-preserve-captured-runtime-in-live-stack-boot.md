---
title: "B5 D1 preserve captured runtime in live stack bootstrap"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["platform"]
target_files:
  - "platform/src/main/kotlin/io/mazewall/ffi/internal/RealNativeStackContexts.kt"
  - "platform/src/test/kotlin/io/mazewall/ffi/internal/RealNativeStackContextsTest.kt"
target_symbols:
  - "RealNativeStackContexts"
open_questions: false
---

# 🟡 [Severity: HIGH]: B5 D1 preserve captured runtime in live stack bootstrap

**Context:**
Bounded first B5 D1 unit: preserve two-argument bootstrap and reject a supplied non-real runtime before JNI or path lookup; no live activation.

**Needed:**
1. Implement and host-test the internal captured-snapshot overload under the accepted B5 fixed2 contract after scoped release.
2. Preserve the old two-argument JVM callable and exactly one snapshot delegation. The new internal overload must not recapture ambient state.
3. Verify non-real Unsupported/RuntimeMismatch before JNI/path/lookup, two-way ambient swaps, and real MissingBinding with retained UnsatisfiedLinkError; reflective mock transport is separate evidence.
4. Run focused platform tests, existing Kotlin ABI comparison and static checks without updating public dumps, adding dependencies or promoting providers.

**Preparation / release state:**
Recovered and reconciled under `.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b5-d1-bootstrap-preparation057/`. `plan-fixed1.md` is the reviewed successor; `controller-review.md` records READY_FOR_EXPLICIT_BOUNDED_RELEASE. No production/test edits or implementation release yet. Only the two named files are proposed; counted preparation, helper association, B6, native/public activation and successful native bootstrap remain outside this increment. B4 acceptance has explicit private limits; profiler retirement issue752 and broader qualification remain open.

---
<!-- id: issue-20261107-145307-348-b5-d1-preserve-captured-runtime-in-live-stack-boot  file: issue-20261107-145307-348-b5-d1-preserve-captured-runtime-in-live-stack-boot.md -->

**Subsequent bounded release:** After controller acceptance of057's reviewed
successor, the current operator Next Move supplies authority for the explicit
controller release recorded in `controller-review.md`. Only the two-file TDD
unit and focused platform test/ABI/static verification are released, with an
exclusive Terra writer and fresh evidence in sibling
`b5-d1-bootstrap-implementation058/`. Earlier pending-release wording records
preparation chronology. No whole B5 lifecycle, full-build checkpoint or native/
public activation is released by this increment.

**Execution dependency:** Released attempt058 stopped before compilation because
Gradle could not create its local file-lock socket service; controller also
observed Docker Unix-socket access denied. No behavioral RED, implementation,
GREEN or focused ABI/static verification exists. Preserve058 patch/logs for
resumption in a permitted execution environment; no security relaxation or scope
reauthorization is requested. The incomplete test addition is removed under
exact ownership/hash checks to retain the original dirty baseline.

**Subsequent environment recovery:** Controller retry003 recorded Docker server
29.9.3 and Gradle `:help` success in the root environment (both exit 0; Gradle
11 tasks, 1 executed and 10 up-to-date). Attempt058 remains blocked historical
evidence, not a behavioral result. Old inherited workers remain restricted; a
fresh Terra sole writer resumed the released two-file TDD scope with evidence059.
Independent final review remains required. No full build, native/public activation,
lifecycle integration or broader qualification follows from this recovery.

**Scoped outcome059:** The controller accepted the bounded platform increment
after independent review and focused verification: 477 tests, zero skips,
failures or errors, including 28 owning cases; ABI comparison, lint and static
checks passed. This issue remains open pending the remaining verification
checkpoint. Full054 does not validate the new059 bytes; a new full build and
later ownership/capture gates remain required. No next lifecycle unit, native/
public activation or broader qualification is released; issue752 remains unknown.

**Full-build checkpoint060:** Accepted with explicit limits for the frozen059
platform bytes: BUILD SUCCESSFUL in 34m52s, 4,335 non-skipped and 45 skipped
cases, zero failures/errors; platform 477 and owning class 28 had no skips or
failures. Three profiler parent endpoint shutdown exceptions recur; individual
process/FD/pump retirement remains unknown under issue752. The 45 skips and
cached producers do not establish runtime qualification. This issue remains open;
owner-bearing D1, actual native readiness and broader qualification are not closed.
Documentation bookkeeping follows completed source postflight and is not part of
the frozen060 source fingerprint.
