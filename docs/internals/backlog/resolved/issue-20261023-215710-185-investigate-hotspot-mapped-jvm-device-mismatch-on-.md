---
title: "Investigate HotSpot mapped JVM device mismatch on Btrfs"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files:
  - "profiler/hotspot-agentless/internal/identity/observe_linux.go"
target_symbols:
  - "discoverProfile"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Investigate HotSpot mapped JVM device mismatch on Btrfs

**Context:**
The single approved public-native attempt on 2026-10-23 rejected before Ready with mapped JVM file identity changed. Retained before/after maps agree on inode33999675 and device00:22 (minor34), while post-attempt stat of the unchanged pinned libjvm reports device0:49 and the same inode/SHA256. Host /home is Btrfs. Original collector Fstat values were not retained, so the exact filesystem mechanism remains unproven. Setup/cleanup Docker helpers succeeded; target exited0; no native retry authorized.

**Needed:**
1. Trace maps-versus-fstat device identity on Btrfs using retained evidence and a focused regression test; preserve fail-closed inode/device, digest and exec-generation guards. Obtain separate source-correction and native-retry scope before rebuilding or rerunning.

**Evidence:** [Public-native rejection and cleanup record](../../reviews/2026-10-23-hotspot-public-native-attempt.md).
Original maps/failure/XML are retained under the native-first workspace's `private/native-attempt-1/`;
`private/docker-permission-1/identity-rejection-analysis.json` separates retained maps from later host stat.
Do not remove device/inode checks or report a filesystem-specific correction as proven.

## Source correction selected and implemented

The operator's subsequent correction requested fixing the failure, not only filing
it. Upstream Linux `fs/proc/task_mmu.c:show_map_vma` reports `inode->i_sb->s_dev`;
Btrfs `fs/btrfs/inode.c:btrfs_getattr` instead assigns `root->anon_dev` to stat.
That invalidates the old direct maps-versus-fstat device equality assumption.

Both canonical and experimental `discoverProfile` paths now compare the opened
target-root file against stat of the kernel's selected map_files entry: stat
device and inode must both match, and the maps inode still must agree. Full
SHA256/build-ID/ELF/profile, PID/birth/cgroup/namespace and exec-barrier guards
remain unchanged. Missing mapping, replaced file and permission denial fail
closed with their original causes. No new dependency, public API or capability
is introduced; map_files uses the already required qualification SYS_ADMIN.

Regression tests first failed against the extracted unchanged guard for the
Btrfs device mismatch, replacement and disappeared mapping. The fix passes those
cases plus inode mismatch and original EACCES preservation under race checks in
both source owners. Receipts: native-first workspace `private/identity-fix-1/`.
Codanna impact lookup was degraded (INDEX_STALE/CODANNA_UNAVAILABLE); direct caller
inspection found the shared strict Discover and ObserveProfile paths in both owners.
The retained old collector/native result is not rebuilt/retried by these host tests.

## Verification and resolution — 2026-10-24

Source correction resolved with behavioral RED/GREEN, both identity race suites,
prepared vet0 for both owners, and passing race-tested package evidence for all
10canonical/14experimental tested packages. Existing capture/workspace tests need
two graph-environment modes; individual failed whole-command receipts remain failed,
with per-package passing provenance in package-verification-coverage.json.
`./gradlew --offline build` completed exit0 in12m18s:228tasks,77executed/151up-to-date.
This is the configured dirty checkout's build, with existing warnings/skips retained
and HotSpot native opt-ins disabled, not a pristine/default-CI or native acceptance claim.
The first tool-deadline interruption remains incomplete, not a passing build receipt.
No rule/baseline weakening, unrelated source fix or native retry was performed.
Original failed native evidence, collector bundle and consumed grant remain intact;
rebuilding/requalifying the corrected collector is a separate integration exit.

References: [Linux maps source](https://github.com/torvalds/linux/blob/v6.18/fs/proc/task_mmu.c),
[Btrfs getattr source](https://github.com/torvalds/linux/blob/v6.18/fs/btrfs/inode.c),
[map_files access contract](https://man7.org/linux/man-pages/man5/proc_pid_map_files.5.html).

---
<!-- id: issue-20261023-215710-185-investigate-hotspot-mapped-jvm-device-mismatch-on-  file: issue-20261023-215710-185-investigate-hotspot-mapped-jvm-device-mismatch-on-.md -->
