# Tier E version-five raw pre-stage gap — 2026-09-28 UTC

**`INCOMPLETE`, one predeclared source-dirty rootful verifier stopped on the
first full-stream fork.** The unchanged 10,000-operation/1,000/s schedule
used CPU `0` for workload, CPU `1` for collector, the pinned image/native
agent and independent benchmark-only raw/main direct-raw-tracepoint BPF
programs. Main exact admission recovered 10,000/10,000 declared IDs and
30,000/30,000 kernel exact/ring-ready records, with zero ring loss. The raw
audit counted **9,997/10,000** target `sys_enter` events on CPU 1, missing
exactly IDs **`5050`–`5052`**. Unique mode did not start.

The target-authored original oracle contains 10,000 distinct `fd=` IDs.
It was copied before the common arm and its checksum matches the printed
receipt. The complete 10,000-row version-five sidecar independently
matches the original oracle's ID set and its own printed checksum; no
other row is abnormal. For each of the three IDs the retained row is:

```
fd    declared raw_pre main_pre raw_post main_post raw_target main_exact
5050  1        0       1        0        1         0          1
5051  1        0       1        0        1         0          1
5052  1        0       1        0        1         0          1
```

Armed raw/main target-`openat` aggregate counts were 10,000/10,003;
raw/main descriptor pre- and post-session totals were 9,997/10,000.
All six explicit diagnostic read/array-lookup failure counters were zero.
The exact installed raw/main BPF instruction digests and kernel program
IDs `61288`/`61287` were retained; optional kernel run-time statistics
were **unavailable**, not zero. These observations place the raw deficit
**at or before the immutable-target/arm/argument/per-ID pre-stage**. They
do **not** prove that the raw BPF program was never invoked or identify
a single kernel branch. Earlier source-dirty missing groups have different
source fingerprints and cannot be combined with this run.

The fail-closed terminal remains `INCOMPLETE`, `policy_eligibility=false`,
`REJECT_DEFAULT_PROMOTION`. The exact stopped runner
`tier-e-trustworthy-c9209e18-a24d-4801-823d-a083d6122576-runner`
and matching `-artifacts` volume were verified retained. `raw-verifier.tar.gz`
preserves the predeclared plan, source/image/program receipts, original
target-authored oracle, full per-ID table, first-fork diagnostic and
recovery record. `SHA256SUMS` seals this report and archive; host originals
remain under `/tmp/tier-e-v5-verifier-1790557126*`. The planned clean
three-pair audited campaign was **not** started because this verifier
failed. The five-pair unaudited default qualification and current-source
native timing gate remain separately incomplete.
