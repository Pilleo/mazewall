# Source-pinned native phase comparison — 2026-09-27

Two **clean**, detached Git clones at `7c9da09e` (the prior v2 passing
revision) and `d72c998b` were compared using predeclared alternating
prior/current schedules. Both snapshots have identical `profiler-native`
sources, container image, native agent SHA-256, C++ compiler, JDK and compiled
`AgentWorkload.class`. All **96/96** individual CTests passed and published
the complete phase counters; the version-two capture/denominator verifier was
applied unchanged. This is native-only diagnostic evidence: it is
`policy_eligibility=false` and does not substitute for the full rootful gate.

| Docker placement | Prior runs complete | Current runs complete | Prior full-stack median (ns) | Current full-stack median (ns) | Prior unique-stack median (ns) | Current unique-stack median (ns) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Unrestricted, five each in AB/BA order | 2/5 | 0/5 | 2,527,300 | 4,229,335 | 2,357,700 | 4,009,839 |
| `--cpuset-cpus=0,1`, three each in AB/BA order | 3/3 | 3/3 | 1,646,816 | 1,665,263 | 1,673,249 | 1,713,363 |

The pinned series stayed below the unchanged historical 125% limits in
**every** full and unique arm, with the current median 1.01x and 1.02x the
prior median respectively. Unrestricted timing is unstable even for the
previously passing **identical native binary**; the experiment does not show
a native-code regression. CPU placement is an explicit input for the new
rootful profile at `config/testing/tier-e/rootful-gate.json`. The baseline
and original unpinned failure receipts are retained; neither was rebaselined
or erased. Other host activity was not isolated, so this result does not prove
that CPU pinning removes all scheduling variance or qualify performance
promotion.

`manifest.json` records both predeclared schedules, every mode median, source
digests, run IDs and actual agent/image identities. `raw-ctest.tar.gz`
contains the original 96 individual CTest outputs, phase verdicts, terminals,
identities, and recovery receipts. `SHA256SUMS` seals the diagnostic.
