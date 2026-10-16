# Version-three pre-decode target-openat development pilots — 2026-09-27

**Development-only, no qualification.** Two distinct source-dirty opt-in
10,000-operation/1,000-per-second full+unique schedules ran against the same
rootful Docker image/native agent and target CPUs `0,1`. They have different
source fingerprints and are **not** a paired experiment. Their different
outcomes cannot be used as a rate comparison or to blame noise. Neither
changed the unaudited five-pair decision or `policy_eligibility=false`.

1. **Unarmed prototype (`08ce8c39…`):** Main full and unique operation
   oracles matched 10,000/10,000 and the normal terminal was `COMPLETE`, but
   the independent raw audit for full stream saw 9,993/10,000 target IDs,
   missing `4021`–`4027` on CPU 1, with no argument-read error or ring loss.
   Its pre-decode raw/main aggregates were 9,995/10,002. The programs
   attached sequentially and these counters had unequal starting windows;
   their difference is not proof of eight dispatched or missing events.
   Unique mode had 10,000/10,000 raw entries and matching early aggregates
   of 10,002. The successful operation terminal did not qualify the raw
   denominator as complete.
2. **Shared-arm probe (`bcf8a424…`):** Both programs' counter window opened
   after attachments and audit maps were ready. The first full-stream fork
   stopped `INCOMPLETE` before unique mode: raw audit counted 10,000/10,000
   selected target IDs on CPU 1, while the main low32 and exact maps had
   zero counts for **IDs `4410`–`4417`**. Raw/main armed early target counts
   were 10,002/9,994; exact submissions were 9,992, kernel exact/ring-ready
   were 29,984/30,000, ring loss was zero. The common-window eight-count
   aggregate gap matches the eight missing declared IDs but does **not**
   prove whether the main program did not execute or a session/target gate
   before its early counter diverged. The report printed the failure before
   exiting nonzero. The exact stopped runner
   `tier-e-trustworthy-3c52beac-e0fd-43b3-8f5a-ce70149aba7c-runner` and
   matching `-artifacts` volume were verified retained.

`raw-pilots.tar.gz` preserves both predeclared plans, exact source manifests,
fork logs, JSONL/terminals, first-failure recovery receipt and Gradle logs;
the large runtime bundles are not copied. `SHA256SUMS` seals this report,
manifest and archive. Full host-local diagnostic trees remain at
`/tmp/tier-e-predecode-v3-smoke-1790540584*` and
`/tmp/tier-e-armed-v3-smoke-1790541641*`. The open
[pre-admission issue](../../backlog/issue-20260920-232238-326-diagnose-intermittent-tier-e-pre-admission-observa.md)
tracks the **next clean committed-source diagnostic**, which must stop on
the first missing audit denominator instead of rerunning until green.
