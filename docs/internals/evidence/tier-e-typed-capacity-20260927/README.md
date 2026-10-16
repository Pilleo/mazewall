# Typed capacity profile and isolated Gradle cache — 2026-09-27

`config/testing/tier-e/capacity.json` selected the Docker image, distinct
workload/collector CPUs (`0,1`), one 1-second 1,000/s pilot, exact operation
and retention limits, and disabled decision/noise escalation. Two bounded
profile-selected rootful Docker runs finished with a complete pilot, eligible
stage, zero escalation and `policy_eligibility=false`. Both pinned the same
source fingerprint, image ID and native agent SHA-256. These are
**source-dirty development smokes**, not a capacity ceiling or a historical
comparison.

The first two attempts never reached a measured target because the bootstrap
Gradle invocation shared the checkout's project-cache lock with an unrelated
host daemon. The bundle launcher and nested Kotlin builder now use separately
owned project-cache directories. Repeating the profile then completed twice
without killing or modifying the operator's Gradle process. In the second
run the bundle's own recorded native build took **234 ms** and its runtime
installation was **0 ms** (reused by the source-keyed bundle cache). The
overall wall time was still minutes: bootstrap Gradle and fresh kernel
workloads are distinct costs and are not represented by the bundle timer.

`manifest.json` records the exact two successful identities, policy-ineligible
terminals and bundle stage timings. `raw-capacity.tar.gz` retains both stage
manifests, event histories, result JSONL and terminals; the checksum ledger
seals these receipts. It does not contain the aborted bootstrap attempts or
large native bundles, which remain host-local.
