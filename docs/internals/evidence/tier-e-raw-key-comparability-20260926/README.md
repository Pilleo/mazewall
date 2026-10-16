# Tier E raw-key phase comparability — 2026-09-26

**Verdict: HISTORICAL PHASE BASELINE NOT COMPARABLE TO CURRENT FULL-STREAM
CANONICAL-CACHE WORK.** No native behavior, safety check, benchmark threshold,
policy eligibility, or default mode was changed. The rootful gate remains
`INCOMPLETE` against its original `FULL_RAW_KEY_NS=440912` (125% limit 551140).

## Controlled sources and workload

- Historical `ce31883b1` and current `d2dd207c4` source were built **Debug**
  inside the same rootful `mazewall-tier-e-rootful:latest` Docker container
  with `/work/historical` and `/work/current` build directories, CPU set `0,2`,
  JDK 25, and Ubuntu c++ 15.2.0. Exact full identities, native build logs, and
  commands are retained in `raw-samples.tar.gz` (`owner.env`, the two
  `*-ctest-definition.txt` files, and `*-{config,build}.log`).
- Five alternating historical/current `agent_phase_benchmark_full` CTest pairs
  all exited zero, reported 514 stack captures and zero stack failures. No
  failed samples were discarded. Historical/current `raw_key_ns` medians were
  **360993/640989 ns**, while their respective median
  `raw_key_ns + logical_metadata_ns` values were **7565972/1108453 ns**.
- The historical full-stream command uses
  `benchmark-phases,mode=full-stream`; current full-stream also names a
  definition file. Removing that definition-file option from current source
  left 504 cache hits and a 589986 ns three-sample raw-key median: the file
  option does not account for the path change.

## Isolating the measurement change

At `ce31883b1`, the canonical lookup in `agent.cpp` was limited to
`unique_stack_syscall`, so the full-stream raw-key clock measured key
construction, not a cache lookup. `c0b9615a` expanded the lookup to
full-stream (now 504 hits/10 misses for the same 514 captures) and moved most
formerly logical metadata work into the raw-key measurement interval.

- Five alternating **current enabled/disabled** cache runs, with unchanged
  source/build/image, had raw-key medians **1331802/35556 ns**. Disabled cache
  rebuilt logical metadata on 514 misses and had a median 20924007 ns logical
  phase; enabled cache reduced that phase to a median 1000705 ns. Disabling
  cache to satisfy the raw-key threshold would regress total work.
- In a **diagnostic-only** copy of current source, nested hash/equality/guard/
  generation timing was removed without altering lookup semantics. Five direct
  JVM pairs each exited zero with 514 captures and 504 cache hits. Current/
  diagnostic raw-key medians were **1549117/1174091 ns**. Nested timing adds
  cost but does not explain the full gap. The diagnostic CTest wrapper exits 8
  because it requires positive nested counters; direct JVM exits were zero.
- Three unchanged current unique-edge CTest samples are retained separately;
  its former raw-key baseline likewise included a cache lookup. A gate change
  must consider both modes and account for lookup cost explicitly.

These five-pair and three-sample controls diagnose **metric ownership**, not
decision-quality workload performance. `comparison.json` records every sample,
exit, metric, and identity. `raw-samples.tar.gz` contains their unedited logs
and the isolated diagnostic agent source; the diagnostic build was never used
to qualify the rootful gate. The separate gate failure and Debug/Release
controls remain in [the open regression issue](../../backlog/issue-20260926-133258-892-investigate-rootful-tier-e-raw-key-phase-regressio.md).

## Next gate decision

Do not change `FULL_RAW_KEY_NS`, silently disable the cache, subtract measured
lookup time, or declare a pass using total work alone. A proposed new
measurement contract would need an independently counted canonical-cache
lookup phase **and** a fixed, historically anchored total-work bound so cost
cannot simply move out of `raw_key_ns`; unique-edge also needs a comparable
contract. That is a separate operator decision. Until approved and verified,
the original rootful gate remains failed, dictionary/full-stream remains the
default, `policy_eligibility=false`, and `REJECT_DEFAULT_PROMOTION` stands.
