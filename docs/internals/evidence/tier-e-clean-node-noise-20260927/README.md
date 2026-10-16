# Clean-source default node-noise qualification attempt — 2026-09-27

**Default five-pair run: `INCOMPLETE`.** A detached `b8901ed7` source checkout
used the typed `config/testing/tier-e/node-noise.json` profile, rootful Docker,
CPUs `0,1` for the target and CPU `2` for the independent three-second Java
procfs/syscall loop. Raw-entry and exact-admission audits were **off**. The
first full-stream target recovered **10,000/10,000** declared operations; its
unique-edge fork missed **5** (`9632`–`9636`) with zero operation-oracle
permission to downgrade. The attached noise sample and observed first-fork
workload PID were retained, but **no completed pair** was published to the
default `results.jsonl`. The exact runner was stopped and its volume retained
for recovery. This failure cannot qualify the predeclared five-pair schedule
or a capacity ceiling.

A separately configured opt-in **one-pair** diagnostic on the same clean
source, image, native binary and CPU set completed both modes with
**10,000/10,000** declared operations, raw `sys_enter` entries, and exact
admission submissions for each fork. A third, **unaudited no-noise** control
also completed both 10,000-operation modes. These independent observations
do not prove the noise loop caused the intermittent pre-admission gap: earlier
no-noise controls missed operations, and audit instrumentation changes timing.
There is no cross-series speed ranking or policy input. The open
[pre-admission issue](../../backlog/issue-20260920-232238-326-diagnose-intermittent-tier-e-pre-admission-observa.md)
must be resolved with exact-accounting evidence before multi-pair noise
qualification can pass. Historical baseline and operation denominators were
not changed.

`manifest.json` compares exact source fingerprint, image ID, agent SHA-256,
CPUs and terminals for all three arms. `raw-diagnostics.tar.gz` retains
selected per-fork JSONL, benchmark logs, observed attachment marker, noise
samples, source identities, terminal and recovery receipts. `SHA256SUMS`
seals the compact archive. The incidental checkout-wide dirty count from an
untracked Gradle cache did **not** affect benchmark inputs: each manifest
reports `source_benchmark_input_dirty_count=0` and the same benchmark source
fingerprint.
