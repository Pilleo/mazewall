---
title: "Enable opt-in Tier E evidence group access and atomic publication"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: [profiler, platform]
target_files:
  - "platform/src/main/kotlin/io/mazewall/platform/native/NativeEngine.kt"
  - "platform/src/main/kotlin/io/mazewall/ffi/internal/RealNativeEngine.kt"
  - "platform/src/main/kotlin/io/mazewall/ffi/NativeConstants.kt"
  - "platform/src/test/kotlin/io/mazewall/ffi/internal/ExtendedAttributeTest.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierEEventJsonlFileSink.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierETerminalReportFileSink.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierEKotlinDaemon.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierEDaemonConfiguration.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierEEvidenceFileAccess.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/daemon/TierEEventJsonlFileSinkTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/daemon/TierETerminalReportFileSinkTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/daemon/TierEEvidenceFileAccessTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/daemon/TierEDaemonFinalizationTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/daemon/TierETargetRegistrationTest.kt"
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierEAllTargetDaemonIntegrationTest.kt"
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierERootfulContainerEndpointIntegrationTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEInstalledFixtureSafety.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEInstalledFixtureSafetyTest.kt"
  - "profiler/src/test/resources/tier-e-fixture-pidfd-stop.py"
  - "profiler/src/test/resources/tier-e-fixture-export.py"
  - "profiler/build.gradle.kts"
  - "docs/internals/designs/profiler/tier-e-split-privilege-deployment.md"
target_symbols:
  - "TierEEventJsonlFileSink"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Enable opt-in Tier E evidence group access and atomic publication

**Context:**
Both evidence sinks need an operator-selected cross-UID access policy without weakening default confidentiality or publication guarantees.

**Needed:**
1. Add failing sink and configuration tests before implementing the shared fail-closed access policy.
2. Parse optional `TIER_E_EVIDENCE_GID` as a numeric host GID once; default both final event and terminal files to `0600`, or set the selected group and `0640` for both. This is distinct from socket access. Set group and exact permissions on the closed, complete temporary file before atomic publication; never chmod/chown directories. Fail closed on attribute or atomic-publication errors. Preserve event no-overwrite separately from intentional terminal replacement; incomplete temporary files are not final evidence.
3. Extend host tests for both sinks, default and invalid/negative GID, injected group/mode/atomic failures with no new final publication, and existing size/record limits. Document operator-owned directory traversal and GID mapping. Declare additional rootful fixture paths in this issue before editing once the exact seam is selected.
4. In a configured rootful separate-daemon fixture, prove both final files are readable by a distinct non-root UID in the selected group and refused to a nonmember; also prove default restriction and final ownership/modes. Root-only readability or a skipped fixture is not proof.

## A4a ACL boundary and minimal native scope

Controller receipt `a4-acl-inheritance-probe.json` reproduced a named nonmember
UID 20001/GID 20002 reading an inherited-access-ACL temporary file after
`chmod 0640`, despite the file's selected group being 1000; at `0600` access was
denied. Mode bits alone do not establish selected-group-only access: widening
the group class activates the inherited ACL mask. Negative acceptance must
include this default-ACL case and verify the named nonmember remains denied.
Do not modify directory/default ACLs.

A4a adds only a default-ENOSYS native filesystem extended-attribute removal
capability, libc `lremovexattr` with captured errno and ENODATA
constant, and an owning platform test (exact paths above). Clear
`system.posix_acl_access` on the owned closed temporary file before group/mode;
only ENODATA (attribute absent) is successful absence. Permission and unsupported
errors reject publication. No production ACL utilities, syscall metadata,
dependencies or breaking engine requirements. Event publication uses atomic
hard-link creation (unsupported filesystems reject); terminal replacement uses
atomic rename only. Evidence directories must remain operator-owned and not
writable by unrelated users throughout the operation. Cross-UID daemon and
inherited-ACL qualification remain pending A4c, not proven by mode assertions.

## Completion gate

A4c declared fixture scope: reuse the existing rootful endpoint preparation and
`TierEContainerRun`/creation closure lifecycle, invoking the installed helper and
separate daemon with the all-target native workload. The two integration paths
above own selected-group, inherited-ACL and default cross-UID checks; build wiring
may forward only the retained qualification artifact directory. No production,
native, protocol or shared controller change is authorized by this amendment.

A4c fix1 declares the four exact test-only safety/regression/resource paths above
before editing them. They provide direct quiesce/export/restore gates, checked
artifact export and pidfd-pinned incarnation/owned-cgroup signaling with cheap
host fault regressions. Reuse `retainOnFailure=true` and stopped-source recovery;
do not edit the existing owner/lifecycle utilities or production/native code.

A4a review fix scope includes the two daemon test paths above and the existing
daemon delivery/rejection boundary. Required durable event failures must retain
their actual exception in the finalization outcome, attempt one incomplete
terminal report, and reject successful control completion. Rejected stale,
exited or reused registrations must retain/log terminal publication failures
without acquiring a resource owner. Both routes use fatal-first failure folding;
sink-local ACL, default access and atomic no-clobber behavior remain unchanged.

Record test-first failures and focused sink/configuration green tests, `:profiler:test`, actual rootful cross-UID execution and its environment, then the full `./gradlew build` completion gate. Keep all-target capture opt-in and evidence policy-ineligible; no default or eligibility promotion. A4a implements the host policy/tests. A4c executed the configured local rootful installed-command matrix: selected GID 20002/exact0640, default0600 with socket GID still set, UID20003/group-member and UID20001/nonmember readers without capabilities, both files and inherited named-user ACL counterexample/negatives. All four matrix cases and six existing identity/artifact negatives passed (10 executed, no skips). Focused host tests: 69; forced host suite: 1512 executed, four existing optional skips. Evidence is retained in the task workspace's `a4c-report.md`, `a4c-matrix.json`, and `a4c-runs-final/`; reproducible commands/support limits are in the deployment guide. Initial fixture failures and exact owned recovery receipts are retained, not erased. The issue stays open for independent review and controller-owned combined build/integration; no host sudo execution is claimed.

A4c fix1 addresses review Important1–3 without production/owner/lifecycle changes:
failed writer stop blocks export/chown, mandatory export/local-write failures
retain stopped source via the existing retain-on-failure seam, and pidfd signaling
validates incarnation plus immutable owned collector cgroup before sending SIGTERM.
Fourteen new host safety regressions pass (100 focused total); forced host suite
1526 executed/four optional skips; all four affected rootful access/ACL cases pass
again, no skips. Earlier positive samples did not qualify these fault branches.
See appended fix1 report and `a4c-fix1-*` receipts; independent re-review/full build
remain required and this issue stays open.

A4c fix2 is limited to residual Important1: the already-declared integration,
test helper and host test paths will share the actual daemon-stop decision.
Any launch attempt requires recorded startup identity even if its exec client
has exited; missing identity must block export and retain recovery ownership.
No new source paths, owner/lifecycle, exporter, pidfd resource or production change.

Fix2 now requires validated startup identity before any client-liveness decision
after a recorded launch attempt. Its actual shared stop decision has six new
deterministic cases, including dead owned client/missing identity with no export,
chown, rm or successful cleanup receipt. Focused safety/owner tests: 37 passed;
forced host suite: 1532 executed/four existing optional skips; renewed affected
rootful access cases: four passed/no skips. Earlier ten/four successful samples
remain separate and did not qualify this branch. Re-review and combined build
remain pending; see appended round2 report and `a4c-fix2-*` evidence.

The current combined full gate failed at `:profiler:rootfulContainerTest` (26
executed, one failure, no skips): the retained `[1] group` sample had 19/20
`loadTemplate` attributions. See the [open investigation](issue-20261103-190440-355-investigate-retained-all-target-19-of-20-loadtempl.md).
Earlier successful evidence remains distinct; A4 completion is not qualified.

---
<!-- id: issue-20261103-134635-250-enable-opt-in-tier-e-evidence-group-access-and-ato  file: issue-20261103-134635-250-enable-opt-in-tier-e-evidence-group-access-and-ato.md -->
