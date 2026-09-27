# Separate clean-source raw-entry and exact-admission diagnostic — 2026-09-27

**Instrumented diagnostic: `INCOMPLETE`, two of three predeclared pairs.** This
run used the same clean committed `3899dc9b` source, native agent, rootful
Docker image, CPUs `0,1`/`2`, and 10,000-operation 1,000/s full+unique
schedule as the separately retained **unaudited** AB/BA series. Only this
diagnostic enabled benchmark-only raw `sys_enter` and exact-admission audits.
It did not replace the default qualification or change the operation oracle.

Pairs 1 and 2 completed under instrumentation. In pair 3, full stream again
matched 10,000/10,000; unique mode matched **9,993/10,000**, missing IDs
**2904–2910**. The raw audit observed **10,000/10,000** declared operations
on CPU 1, whereas the exact-admission audit submitted **9,993**. Its per-
descriptor diagnostic listed those exact seven IDs with **zero** counted
admissions. The unique-mode record showed 29,986 kernel exact admissions,
29,986 ring-ready records, and zero ring loss. The audited handler-entered
count was 10,275 across target and unrelated syscalls; only 9,993 entries
reached the target/session-matched stage. Aggregate wrong-target and map
lookup counters include unrelated syscalls and **must not** be attributed to
the seven IDs without per-event proof.

This localizes a gap between the raw target syscall observation and the exact
per-descriptor admission/dispatch result. It does **not** distinguish a main
program dispatch gap from a pre-counter early filter or prove one eBPF
rejection branch. The noisy measurement overlaps the first full-stream fork;
the later unique fork's missing IDs do not prove unique-mode noise causality.
The exact runner and volume from the failure were stopped/retained; its
terminal and the aggregate remain `INCOMPLETE`, `policy_eligibility=false`.
Two earlier audited pairs cannot qualify the default unaudited lane. The
historical baseline, operation denominator and default-promotion decision
remain unchanged.

`manifest.json` records the predeclared audit plan, source/image/native
identities and each reached target. `raw-audit.tar.gz` holds per-fork JSONL,
benchmark logs, first-fork marker, separate noise samples, terminals and
recovery receipt. `SHA256SUMS` seals the small original receipts. The
[pre-admission issue](../../backlog/issue-20260920-232238-326-diagnose-intermittent-tier-e-pre-admission-observa.md)
remains open pending a demonstrated ingress/rejection boundary and regression
test; this diagnostic is not permission to change the core BPF design.
