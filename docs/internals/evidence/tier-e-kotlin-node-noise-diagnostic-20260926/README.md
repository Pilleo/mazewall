# Kotlin node-noise diagnostic — 2026-09-26

**Scoped outcome:** A bounded one-pair **audit-enabled** rootful Docker run
completed the 10,000-operation target oracle, recorded separate baseline and
attached one-second Java `/proc/self/stat` samples, and published
`policy_eligibility=false`. The observed `workload_pid` was live both before
and after noise sampling and matched the first profiled full-stream fork. Its
readiness marker was saved outside the changing target artifact directory;
the final exported marker may belong to a later fork and was not substituted
for that observed identity.

Raw `sys_enter` audit and exact-admission audit were **opt-in benchmark-only**.
The completed full-stream fork reported 10,000/10,000 declared operations,
10,000/10,000 raw `sys_enter` observations, and 10,000 submitted exact
admissions. No baseline, retention budget, operation denominator, or Tier E
production attachment was changed. Both source and benchmark terminal records
were complete. This Java noise loop is not compared numerically with the
historical Bash-loop series.

**Qualification limitation:** An earlier same-source, same-image audit-enabled
control and attached target both independently passed exact target accounting;
the then-active node controller could not finish its pair because it expected
an unsuffixed `dictionary_full_stream` row. The repaired controller passed a
separate one-pair audit-enabled smoke, not a five-pair decision. Without
audits, fixed-schedule rootful targets intermittently miss declared IDs;
another controlled noisy run missed two, and a separate control had also
missed six. That **default** path remains `INCOMPLETE` and issue
[`issue-20260920-232238-326`](../../backlog/issue-20260920-232238-326-diagnose-intermittent-tier-e-pre-admission-observa.md)
remains open. A separate audited failing run saw 10,000/10,000 raw entries but
only 9,994 submitted exact admissions, with two explicit wrong-target
rejections. This localizes part of the gap after raw syscall observation; the
additional unaccounted IDs are **not** attributed to any one BPF branch.

`manifest.json` lists run/image/native identities and scope. The
checksum-sealed `raw-diagnostics.tar.gz` contains selected target JSONL,
manifests, terminal reports, observed attachment marker and paired noise
samples without repackaging large bundles. These samples were captured from
recorded, source-dirty intermediate worktrees before the final Kotlin parser
refactor; they are not clean-revision release measurements. This is a
diagnostic smoke, not a capacity ceiling or a default-promotion decision.
