# Tier E explicit-image-pin source: correctness passed, phase stopped — 2026-09-28

**Overall `INCOMPLETE`: the rootful aggregate gate did not run.** The new
opt-in version-two pinned-image gate source was committed on `fast-master`
as `8a0d6d32b2dd14b8bf366c58ac0fb7bbcb6bd76e`. It accepts an exact
Docker image ID without rebuilding a tag that lacks the legacy
Containerfile label, while leaving version-one profiles unchanged.
This tooling change altered the benchmark source fingerprint, so the
prior `fbbaad44` generic-kernel correctness and timing receipts were
not reused as this source revision's acceptance evidence.

The separately [predeclared fixed plan](./correctness-raw.tar.gz) used
Ubuntu `7.0.0-34-generic`, the same immutable rootful image
`sha256:fc5bf5e14796e5187be873b84c1ce4049ce240415d93e4bdc8307fbbe0870c56`,
Release agent SHA-256
`05f0f2a768b66b0659347f265f0db80b37a9b0acda0e0e0a60c3fba513ecd5ff`,
and target/collector CPUs `0,2` with noise on CPU 3. The new source
fingerprint is
`cc967ae54480b4e5341d510f161ffc40b84f825ef5c025ed5bb1f7fc1ebbb6b6`.

**Unaudited correctness completed:** all five fixed AB/BA pairs and all
20 dictionary full/unique forks were exactly **10,000/10,000** with
zero missing, duplicate, wrong, unresolved or unclassified operations
and zero ring loss. Every reached target used the same source, image,
agent and CPU set, reported a clean benchmark-input tree, and verified
CPU-0 affinity for existing target JVM threads before gate release.
Each noisy arm's three-second PID-bound sample belongs to its first
full-stream fork; unique-mode overlap was not established.

**Native timing failed the unchanged historical bound in phase 1.** Six
fresh 100-iteration CTests executed successfully (three full, three
unique). Full mode's median `GetStackTrace` was **2,022,989 ns** and its
version-two verdict passed. Unique mode's three `GetStackTrace` samples
were **3,157,189 / 2,557,668 / 3,711,061 ns**; median **3,157,189 ns**
exceeded the unchanged **2,307,381 ns** limit. The unique phase verdict
and phase-1 terminal were `INCOMPLETE`. Phase runs 2–3 and the pinned-image
aggregate gate were **not run**, per the plan's first-failure stop rule.
No timing baseline or 125% tolerance was edited. The Debug native agent
SHA-256 `ff1e640c079de05ba407eab963cfb8939553e7f49390e60fdb20645faa3f790a`
and workload-class hash were identical to the earlier `fbbaad44`
generic-kernel timing receipt, as were image, compiler, JDK and CPU set.
The source revision/digest differed; timing variability or source
causality is **not** established by that comparison.

`correctness-raw.tar.gz` contains the original five-pair plan/profile,
all ten source-bound targets, 20 child logs, noise samples, PID markers,
pair/guest terminals and source manifests. `native-phase-raw.tar.gz`
contains the unchanged historical baseline, exact Debug package
manifest/binary, all six raw samples, passing full and failed unique
verdicts, first-failure terminal and recovery receipt. `SHA256SUMS`
seals both archives and this report. Complete originals remain under
`/home/leanid/Documents/tier-e-generic-pinned-rootful-20260928/`.
Exact failed timing runner
`tier-e-phase-a019d0fb-1203-47ea-9e5f-8c8a2ccc1f5d-runner` and
its `-artifacts` volume remain retained. `policy_eligibility=false`,
`REJECT_DEFAULT_PROMOTION`; no backend, image, kernel or timing threshold
was silently changed to obtain an aggregate pass.
