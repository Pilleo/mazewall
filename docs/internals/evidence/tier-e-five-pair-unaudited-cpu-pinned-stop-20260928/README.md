# Five-pair unaudited Tier E AB/BA gate — 2026-09-28 UTC

**`INCOMPLETE`: pair 1 completed; pair 2 stopped in its noisy-first
unique-edge fork.** No control arm for pair 2 and no arms of pairs 3–5
ran. The existing Kotlin `tierENodeNoiseQualification` controller enforced
the original schedule `control,noisy | noisy,control | control,noisy |
noisy,control | control,noisy`, 10,000 operations per mode at 1,000/s,
one full and one unique fork per target, and three-second attached-noise
sample. Both raw-entry and exact-admission audits and the invocation
witness were **disabled**.

Committed source was `fast-master` `fbbaad44bb2be272f2be863a4a049998a8894903`.
The clean checkout reported zero benchmark-input edits. Every reached
target shared source fingerprint
`4c83180fc562f9b430666ede7712b9d63db92c9669ac89538ed37f009a69ae0a`,
rootful image ID
`sha256:fc5bf5e14796e5187be873b84c1ce4049ce240415d93e4bdc8307fbbe0870c56`,
native agent SHA-256 `05f0f2a768b66b0659347f265f0db80b37a9b0acda0e0e0a60c3fba513ecd5ff`,
and target/collector CPUs `0,2`, which are on separate cores. Host noise
was bound to CPU 3. The kernel was `7.2.8-x64v3-xanmod1`; the global BPF
statistics switch remained disabled. Every reached child checked 13
existing target JVM threads pinned to CPU 0 before its workload gate.

Pair 1's control and noisy target each completed **10,000/10,000** full
and unique declared operations, with 30,000 exact admissions and
ring-ready records per fork and zero reported ring loss. Its completed
pair record was retained before pair 2 started. Pair 2's noisy target
completed full stream at 10,000/10,000. Its subsequent unique-edge fork
completed the target's 10,000-call workload but accounted for only
**9,999/10,000** declared operations. The original failing report names
**directory FD 6871** as missing, with one candidate, one acknowledged
representative, 9,998 explicit suppressions, **29,998/30,000** kernel
exact admissions and ring-ready records, and **zero ring loss**.
The operation deficit is at or before exact admission; these unaudited
counters do not prove an individual BPF rejection branch.

Pair 2's three-second host-noise baseline and attached sample were
retained with the target's attachment-ready PID. The overlap check
applies to its **first full-stream fork**, not the later unique-edge fork.
This failure does not establish that noise caused the unique-edge miss.
Nor do the four shorter CPU-matched positive arms in the separate
[bounded comparison](../tier-e-sustained-four-arm-cpu-matched-20260928/README.md)
negate this first-failure result.

`raw-gate.tar.gz` preserves the predeclared plan and profile, rootful
logs, completed pair-1 artifacts and result row, reached pair-2 control
files, both noisy samples and attachment markers, the first failing
full/unique child logs, source manifests, `results.jsonl`, terminal and
recovery receipt. `SHA256SUMS` seals the report, manifest and archive.
Host originals remain under `/tmp/tier-e-five-pair-fbbaad44.result/`.
The exact failed runner
`tier-e-trustworthy-fb35b8fb-a965-4ea0-85e2-7da82f380ebe-runner`
was verified exited 1; its owned `-artifacts` volume remains retained.
`policy_eligibility=false`, `REJECT_DEFAULT_PROMOTION`; the five-pair
gate did **not** qualify the default. Native timing and Podman parity
remain separate.
