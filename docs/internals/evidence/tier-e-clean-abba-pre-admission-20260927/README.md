# Clean-source AB/BA default node-noise qualification attempt — 2026-09-27

**Verdict: `INCOMPLETE`, two of five planned pairs.** Before measurement,
`qualification-plan.json` pinned committed `3899dc9b`, the benchmark source
digest, rootful Docker image ID, Gradle-built native-agent SHA-256, CPUs
`0,1` (target) / `2` (unrelated process), and the unchanged 1,000/s,
10,000-operation full+unique schedule. Raw-entry and exact-admission audits
were **disabled**. The Kotlin scheduler predeclared
`control,noisy | noisy,control | control,noisy | noisy,control | control,noisy`
and stopped at its first incomplete arm. Both arms in pairs 1 and 2 completed
with **10,000/10,000** full **and** unique operation oracles. Their two rows
were saved before proceeding to pair 3.

Pair 3's unaudited no-noise **control completed both modes**. Its noisy target
completed full stream with 10,000/10,000, but unique mode missed declared
operation IDs **1777–1778**, matching 9,998/10,000. It published no third
pair. The failing fork reported **29,996 kernel exact admissions**, **29,996
ring-ready records**, and **zero ring loss**, versus 30,000 admissions in
complete source-matched forks. Raw `sys_enter` and per-descriptor admission
audits were off: these counters locate the deficit at or before exact
admission, **not** at a proven BPF rejection branch. The unique fork followed
the three-second attached-noise observation of the **first full-stream fork**.
Its missing IDs do not prove unique-mode noise overlap or noise causality.

All six reached targets recorded the same committed source revision,
benchmark-source fingerprint, image ID, native SHA-256 and CPU set; all
benchmark input dirty counts were zero. The aggregate terminal and failed
target were `INCOMPLETE`, `policy_eligibility=false`. The failed exact runner
was stopped and its artifact volume retained, with a recovery receipt.
Earlier complete pairs remain in `results.jsonl`; they do not qualify the
planned five-pair decision. No denominator, historical phase baseline,
capacity ceiling or default-promotion decision was changed.

`manifest.json` reconciles all three reached control/noisy arms and the
predeclared plan. `raw-diagnostic.tar.gz` retains the selected original
target manifests, result JSONLs, benchmark logs, source identities, attachment
marker, separate noise samples, terminals and recovery receipt.
`SHA256SUMS` seals these bounded receipts. The still-open
[pre-admission issue](../../backlog/issue-20260920-232238-326-diagnose-intermittent-tier-e-pre-admission-observa.md)
requires a demonstrated rejection boundary and a regression test before
another default qualification attempt.
