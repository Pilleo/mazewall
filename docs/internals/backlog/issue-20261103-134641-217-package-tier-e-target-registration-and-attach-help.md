---
title: "Package Tier E target registration and attach helper"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierETargetRegistration.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/ElfSymbolOffset.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierETargetControlClient.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierETargetHelper.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/daemon/TierETargetRegistrationTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/daemon/TierETargetHelperTest.kt"
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierEAllTargetDaemonIntegrationTest.kt"
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierERootfulContainerEndpointIntegrationTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEInstalledFixtureSafety.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEInstalledFixtureSafetyTest.kt"
  - "profiler/src/test/resources/tier-e-fixture-pidfd-stop.py"
  - "profiler/src/test/resources/tier-e-fixture-export.py"
  - "profiler/build.gradle.kts"
  - "docs/internals/designs/profiler/tier-e-split-privilege-deployment.md"
target_symbols:
  - "TierETargetRegistration"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Package Tier E target registration and attach helper

**Context:**
Operators need one installed helper that derives strict v1 registration identity and uses existing control framing without manual ELF offsets.

**Needed:**
1. Add failing CLI registration and control-framing tests before implementing the packaged helper.
2. Package `tier-e-target-helper register` and `attach`, reusing the strict existing v1 parser, ELF symbol resolver and control client as needed. The operator selects registration ID, host PID, collector-visible agent library, session tag, artifact filename and registration output; derive `/proc` starttime and marker ELF offset and validate the written v1 registration. Never overwrite an existing output or follow its symlink. Attach uses the existing full-stream modified-UTF framing; preserve the `tier-e-daemon` launcher and protocol v1.
3. Reject exited/reused PID, locally invalid values, mismatched registered wire session/library, unsafe filenames, malformed replies and output conflicts; return nonzero on `ERR`. Require collector PID/filesystem visibility rather than claiming numeric PIDs prove namespace translation. Do not introduce automatic permissions, dynamic PID authorization, another protocol, dependency or configuration language. Amend exact scope before editing any additional files; source/public guidance classification may need amendment after launcher/API checks.
4. Document a copyable installed launch, register, attach, run, detach and inspect sequence, including host PID and mount visibility, separate definition path, privileges, supported bindings/profiles, diagnostics, terminal evidence and cleanup. All-target observations do not imply exact attribution or policy eligibility.

The helper uses strict process observation with a shared pure parser in `TierETargetRegistration.kt`; the existing nullable daemon observer and watcher remain unchanged. Their pre-existing error/fatal swallowing is tracked separately in [the process observation issue](issue-20261103-152002-779-preserve-process-observation-errors-and-watcher-re.md). Control v1 cannot attest remote registration ID, artifact filename or whether registration is configured; use the same operator-owned registration and trusted socket.

## Completion gate

A4c declared fixture scope: existing rootful endpoint preparation calls the
all-target separate-daemon fixture using actual installed register/attach/daemon
commands. Reuse owned container/creation closure cleanup; retain source/runtime,
registration, identity, reader and failure evidence. Build changes, if needed,
only forward the qualification artifact directory. No new controller or protocol.

A4c fix1 declares the four exact test-only safety/regression/resource paths above
before edits: direct cleanup gates, verified export and pidfd-pinned collector
signaling, with host fault tests before any further rootful acquisition. Reuse
the unchanged existing lifecycle's retain-on-failure/stop-for-recovery seam;
production, protocol and owner/lifecycle utility code stay unchanged.

The helper and host tests are implemented; A4b host/packaging and correction reviews have no blocking findings. A4c's actual installed register/ATTACH and separate daemon/native workload passed four rootful access/ACL cases, with exact 20+20 managed openat observations, ambient rows and reconciled capture/export counts. Wrong session and namespace-local PID are actually rejected before admission; the existing rootful stale/reuse/artifact negatives also passed (10 final rootful tests, no skips). Focused host tests: 69; forced profiler suite: 1512 executed/four optional skips. See task workspace `a4c-report.md`, `a4c-matrix.json`, `a4c-runs-final/`, and the deployment guide for commands/identities/cleanup and retained initial failures. Host-sudo prose now says attempted termination and requires physical exit verification even after successful SIGTERM; that illustrative host recipe was not executed. V1 still does not attest remote registration mode/ID/artifact, and operator-controlled registration/socket/mappings remain necessary. Keep the issue open for independent review and the controller-owned combined full build/integration. Retain opt-in defaults and `policy_eligibility=false`.

A4c fix1 corrects Important1–3 in test-only orchestration: strict stop/export gates,
retained failed-source ownership until verified recovery export, and incarnation/
owned-cgroup validated pidfd signaling with no remembered-PID fallback. Fourteen
new host fault regressions, 100 focused tests, 1526 executed host-suite tests (four
optional skips), and four renewed rootful access/ACL cases (no skips) pass. The
reviewed earlier successful matrix did not qualify the old unsafe fault paths.
Fix evidence/report is appended under `a4c-fix1-*`; owner utilities, A4b production,
v1 trust limits and the pending independent-review/controller-build gate are unchanged.

A4c fix2 scopes only residual Important1 in the existing declared integration/
test-helper/test paths: reproduce the dead-exec-client/missing-startup-identity
stop decision, then require identity and verified physical daemon absence before
export independently of client liveness. No additional source paths or changes
to the addressed exporter/pidfd resources, production or owner/lifecycle utilities.

Fix2's shared stop decision rejects missing startup identity after every launch
attempt, including a terminated/unavailable exec client, without export or late
PID learning. Six new decision regressions pass; all 37 focused safety/owner tests,
1532 executed host-suite tests (four optional skips), and four newly renewed
rootful access/ACL cases (no skips) pass. See appended round2 report and distinct
`a4c-fix2-*` receipts; earlier positive samples did not cover this fault branch.
Important2/3 corrections are unchanged. Keep this issue open for re-review and
the controller-owned combined build.

The current combined full gate failed at `:profiler:rootfulContainerTest` (26
executed, one failure, no skips): the retained `[1] group` sample had 19/20
`loadTemplate` attributions. See the [open investigation](issue-20261103-190440-355-investigate-retained-all-target-19-of-20-loadtempl.md).
Earlier successful evidence remains distinct; A4 completion is not qualified.

---
<!-- id: issue-20261103-134641-217-package-tier-e-target-registration-and-attach-help  file: issue-20261103-134641-217-package-tier-e-target-registration-and-attach-help.md -->
