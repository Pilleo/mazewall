# Tier E package-first generic-kernel acceptance — 2026-09-29

**All declared gates `COMPLETE` for one exact configuration; default
promotion remains rejected.** The candidate is committed source
`07dcdb220847044ce83f24f22eec5eb374743548`, clean runtime-source
digest `a4219212fd5185cb73a42f6eaeaa47f2e00113f660ca9c21e3ffeb95d6e1e7df`,
Ubuntu `7.0.0-34-generic` with config SHA-256
`e75c0adab6020e72844d7431cf801a02b94ed32efc251c3b0fb80c021d065180`,
and rootful Docker image ID
`sha256:fc5bf5e14796e5187be873b84c1ce4049ce240415d93e4bdc8307fbbe0870c56`.
Native timing ran with CPUs `0,2`; correctness used workload CPU 0,
collector CPU 2 and noise CPU 3. The Gradle-built Debug agent full SHA-256
was `15914500bf5ecffa8c248aebdebee796dcc1ebc4349f5b54d57544fd7ddf36bd`,
the separately Gradle-built Release agent was
`e3efda537e1f68d5005ea95055f57e6d6b6364fa4fddd97c28697311f93a67f6`,
and both packages carried `AgentWorkload.class` SHA-256
`12ac99613bffb53253c02545063b37f01eb1860657233acf7879f90365d7c0e6`.
All package files were verified against their manifests and these exact
full-binary IDs, together with source/image/toolchain and unchanged
native baseline, were sealed **before the first acceptance CTest**.
Focused host checks and one full Gradle 9.6.1 build passed before
acquisition. The old differently built diagnostic binary was not used.

| Prerequisite | Fixed budget | Result |
| --- | --- | --- |
| Version-two native phases | 3 independently owned runs × 3 full + 3 unique fresh 100-iteration CTests | **3/3 `COMPLETE`**, all 18 raw samples and both mode verdicts/run pass unchanged bounds |
| Original unaudited node-noise gate | Five fixed AB/BA control/noisy pairs, 10,000 operations/mode at 1,000/s | **5/5 pairs, 20/20 forks `COMPLETE`**, exact 10,000/10,000 operations per fork |
| Exact-image rootful gate | One v2 pinned-ID suite, preselected phase-1 reuse | **`COMPLETE`**, source/image/Debug binary/phase receipt and host export verified |

Native full `GetStackTrace` medians for runs 1–3 were **1,569,858 /
2,076,799 / 1,714,126 ns**; unique medians were **1,436,985 /
1,803,931 / 1,640,872 ns**, all under the unchanged historical
version-two limits (unique **2,307,381 ns**). Each run also passed its
raw-key and combined key/lookup/logical bounds. All 18 source-bound
native CTest logs reported 514 captures, zero stack failures and the
expected 504/10 canonical-cache hit/miss population; originals and
owned Docker exports matched byte for byte. The aggregate deliberately
reused **run 1**, not a later favorable result.

In correctness, every full and unique fork recovered all 10,000 exact
operation IDs, with zero missing, wrong, duplicate, unresolved and
unclassified operations and zero ring or retained-result loss. Unique
forks retained one acknowledged representative and 9,999 declared
suppressions. All controls and noisy targets retained the same source
fingerprint, pinned image, Release agent and CPU placement. Each noisy
arm had a PID-bound attachment marker and distinct three-second host
noise samples while its **first full-stream** target was active; this
protocol does not prove overlap with its later unique fork.

The one rootful aggregate admitted the exact immutable image without
rebuilding it. Its phase-origin receipt names run 1 and binds the
unchanged source digest, full Debug agent SHA-256, image and phase-1
terminal; all six reused raw CTests and both phase verdicts matched
their originals byte for byte. The aggregate ran its own native CTest
matrix (**45/45 passed**) and the required profiler host/integration
and fresh-JVM suites. The retained fresh-JVM reports cover **18 tests,
zero failures, errors or skips**. All five rootful capture/transport/
attribution/dictionary/lifecycle dimensions report `PASS`. The phase
CPU set was `0,2`; live JVM integration tests used the documented
unrestricted-CPU placement. The v2 image admission receipt and host
verifier confirmed the tag/immutable ID before and after the suite.

**Decision boundary:** This is a candidate *only for the exact source,
kernel/config, image, native packages, CPUs and protocol above*. The
original failed `8a0d6d32` timing terminal, both later stopped
diagnostics and the `2a886864` native-binary identity stop remain
independently `INCOMPLETE`; none is upgraded by this attempt. The
pre-reboot XanMod gate remains `INCOMPLETE`, and no XanMod patch is
identified as causal. Podman parity remains operator-deferred.
`policy_eligibility=false` and `REJECT_DEFAULT_PROMOTION` remain in
force pending the operator's separate exact-environment support and
promotion decision. A docs-only commit of this report changes the
worktree HEAD; it does not silently transfer this candidate evidence
to that new source revision.

`preflight-raw.tar.gz` retains the clean source fingerprint, checksum-
bound plan/schedule and both **complete** prepared native packages.
`native-phases-raw.tar.gz` retains all three original six-sample runs,
identities and host exports. `correctness-raw.tar.gz` retains all
pair/target terminals, 20 child logs, operation-oracle results, source
manifests, noise samples, PID markers and bundle manifests/checksums.
As in prior Tier E evidence archives, redundant `bundle/runtime` JARs
and per-target copied native library bytes are retained in the **full
off-repo originals**, not duplicated in the review archive.
`aggregate-raw.tar.gz` retains the pinned-image admission, phase reuse,
native/profiler suite logs, fresh-JVM reports and exact terminal. The
complete **424-file** source-bound originals and `SHA256SUMS` remain at
`/home/leanid/Documents/tier-e-package-first-07dcdb22-20260929/`.
