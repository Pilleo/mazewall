# Clean, armed Tier E pre-decode audit diagnostic — 2026-09-27

**One predeclared control/noisy pair `COMPLETE_INSTRUMENTED_ONLY`; default
qualification remains `INCOMPLETE`.** This is a separate benchmark-only
diagnostic from clean `0cb0b3ee`. Before the first target, the plan pinned
the rootful Docker image, native agent hash, CPUs `0,1` for both arms and
CPU `2` for the unrelated noise sample, 10,000 operations per mode at
1,000/s, one full and one unique fork per arm, and `control,noisy` order.
The same selected source fingerprint, image, native agent and target CPUs
were confirmed in both manifests. `policy_eligibility=false`; no phase or
default-path PASS is claimed.

Both control forks and both noisy forks completed the exact 10,000/10,000
workload oracle, independent raw descriptor count and exact-admission
submission, with 30,000/30,000 kernel exact/ring-ready records per fork and
zero ring loss. After a common shared arm gate, *raw and main* target-
`openat` pre-decode aggregates were **10,002/10,002 in all four forks**;
two additional target opens mean this aggregate is not itself the declared
10,000-operation denominator. Each paired benchmark audit verdict was
`PASS`. A three-second unrelated CPU-2 noise sample observed the first
full-stream workload PID alive; no noise overlap is claimed for the later
unique fork. This positive, separately instrumented pair does **not**
reconcile the earlier failed clean v2 and source-dirty v3 descriptor IDs,
nor replace five unaudited AB/BA pairs.

The committed Gradle wrapper is 9.6.1; its host TestKit worker startup is
unreliable. The preinstalled Gradle 9.8.0 binary ran `buildSrc`
verification and resolved the native package on the untouched clean clone.
The cached package's image ID and agent SHA-256 matched the predeclared plan;
runtime and kernel receipts were acquired fresh. `raw-pair.tar.gz` preserves
the plan, profiles, Gradle preflight and arm logs, source state, manifests,
all four child reports, noise sample and terminals. `SHA256SUMS` seals the
archive and source receipts. Host-local originals are under
`/tmp/tier-e-v3-armed-clean-1790542396*`. The open
[pre-admission issue](../../backlog/issue-20260920-232238-326-diagnose-intermittent-tier-e-pre-admission-observa.md)
still requires a demonstrated root cause and a **new unaudited** five-pair
qualification after any justified fix.
