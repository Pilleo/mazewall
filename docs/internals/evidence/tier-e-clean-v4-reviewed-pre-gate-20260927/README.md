# Clean reviewed Tier E v4 per-ID diagnostic — 2026-09-27 UTC

**One predeclared control/noisy pair `COMPLETE_INSTRUMENTED_ONLY`. Default
qualification remains `INCOMPLETE`.** Clean committed `5959b108` pinned one
source fingerprint, exact Docker image and native agent, target CPUs `0,1`,
noise CPU `2`, and 10,000 operations at 1,000/s in one full and one unique
fork per arm. The declared serial order was `control,noisy`, with stop on
the first incomplete arm. The preinstalled Gradle 9.8.0 executable passed
the clean checkout's `buildSrc` preflight and resolved the native package;
the committed wrapper and checkout were not changed. Runtime/kernel
measurements were fresh; the native artifact package is a binary input,
not a cached measurement.

All **four** forks matched 10,000/10,000 independently declared raw IDs,
main exact submissions and target-qualified raw/main pre-/post-session
counts. Every per-ID version-four sidecar contained exactly **10,000 rows**,
was published atomically, and passed the host's schema, SHA-256, sorted-ID,
target-workload-PID and fork-identity checks *before* source-volume
retirement. Each fork had 30,000/30,000 kernel exact/ring-ready records
and zero ring loss. The manifests match source fingerprint, image ID, agent
hash and CPUs across both arms. The noise loop sampled the observed first
full-stream workload PID for three seconds; later unique-mode overlap was
not measured and is not claimed.

`raw-pair.tar.gz` holds the predeclared plan and profiles, clean preflight,
control/noisy arm receipts and logs, all four original complete descriptor
files, terminals and noise samples. `SHA256SUMS` seals the small receipts
and raw archive. Exact host-local originals remain at
`/tmp/tier-e-v4-reviewed-clean-1790552612*`.

This single positive **instrumented** pair does not explain the earlier
intermittent raw/main missing-ID forks, correct their archived failures,
qualify five **unaudited** AB/BA pairs, resolve the native timing failure,
or authorize default policy promotion. `policy_eligibility=false` and
`REJECT_DEFAULT_PROMOTION` remain. See the open
[pre-admission issue](../../backlog/issue-20260920-232238-326-diagnose-intermittent-tier-e-pre-admission-observa.md).
