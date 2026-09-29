# Tier E legacy script retirement audit — 2026-09-29

**Status:** Operator-approved Tier E legacy cleanup executed. Twenty `.mjs` and eight `.py` files, including their self-tests, were removed from current `scripts/`. Tier E remains opt-in, and profiling evidence remains `policy_eligibility=false`.

## Pre-removal inventory and current ownership

The pre-removal repository contained **zero `.ts` or `.tsx` files**. The Node legacy was `.mjs`:

| Scope | Files | Lines | Current owner |
| --- | ---: | ---: | --- |
| Tier E `.mjs` commands, modules and their own tests | 20 | 1,671 | Removed from HEAD; pinned historical Git checkout retains exact bytes |
| Tier E Python commands and their own tests | 8 | 1,577 | Removed from HEAD; pinned historical Git checkout retains exact bytes |
| Other `scripts/*.py` | 9 | 931 | Project hooks, memory/backlog utilities and manual core-evidence checking |
| `exploits/**/*.py` | 4 | Separate demo | Vulnerable-app demonstration |

This classification searches tracked Gradle configuration, active shell launchers, profiler code/tests, CI and project hooks. It excludes unrelated operator-owned `.omo/` and untracked backlog work. `:profiler:test`, `./scripts/lint.sh` and `./scripts/run_tests.sh` do not select the legacy Node/Python Tier E tests. One active shell wrapper invokes `backlog_negative_indexer.py`; project hooks invoke `pre_invocation_sync.py` and `auto_memory_capture.py`. The vulnerable-app demo calls its Python exploit scripts. These are **not** Tier E legacy.

## Retired Tier E batch

**Removed from current `scripts/` together with their self-tests:**

- `scripts/summarize_tier_e_coverage.mjs`, `tier_e_coverage_summary.mjs`, `tier_e_coverage_stack_identity.mjs`, `verify_tier_e_split_coverage.mjs`: Kotlin already owns coverage report/identity and `TierESplitCoverageVerifier` or fixture-verdict admission.
- `scripts/summarize_tier_e_split_fork.mjs`, `tier_e_split_fork_completeness.mjs`, `verify_tier_e_split_operations.mjs`, `summarize_tier_e_split_matched.mjs`: current fork/operation verification and short-matched summaries are Kotlin-owned. The historical short paired-bootstrap output is not a new active release gate.
- `scripts/tier_e_target_cpu_sample.mjs`, `tier_e_collector_cpu_sample.mjs`, `tier_e_long_matched_row.mjs`, `summarize_tier_e_long_matched_controls.mjs`, `verify_tier_e_long_matched_controls.py`, `tier_e_split_source_fingerprint.py`, `probe_tier_e_real_pid_reuse.py`: typed CPU sampling, long-matched row/estimator/full-directory acceptance, source fingerprint and PID-reuse probing already exist in Kotlin. Maintain the strict Kotlin fixtures when removing legacy counterparts.
- `scripts/summarize_tier_e_hybrid_decision.mjs`: historical short 15-pair adoption calculation, no active Kotlin release decision. Tier E/hybrid **cannot become a default**; retain its old measured verdict, not an executable promotion path.
- `scripts/analyze_tier_e_perf_windows.py`: 79-line standalone diagnostic, **not an exact Kotlin port** of time-window leaf attribution. Nothing invokes it in active measurement. It was retired with the old experiment; reintroducing this analytic capability would require a focused Kotlin parser and fixture.
- The matching `scripts/tests/tier_e_*.test.mjs`, `scripts/test_verify_tier_e_split_operations.mjs`, `scripts/tests/test_tier_e_split_source_fingerprint.py`, `scripts/tests/test_probe_tier_e_real_pid_reuse.py`, `scripts/tests/test_verify_tier_e_long_matched_controls.py` and `scripts/test_analyze_tier_e_perf_windows.py` were removed **with** their retired implementations. Kotlin tests remain for the supported contracts.

That is **20 Node and 8 Python Tier E files**, not a request to port 28 inactive interfaces one-for-one. Active Kotlin coverage, operation, long-matched, PID and native timing tests remain.

## Approved historical replay policy

The operator approved pinned-Git replay in place of runnable HEAD copies. Several checksum-bound historical evidence READMEs still name the old files; those **historical instructions remain unchanged** and must be run from their historical checkout. All 28 scripts are verified available in Git at `07dcdb220847044ce83f24f22eec5eb374743548`.

For replay, create a disposable checkout from a clone that includes the pinned revision, then run the saved command **inside that checkout** with its saved evidence inputs:

From the repository root:

```bash
git clone --no-hardlinks . /tmp/mazewall-tier-e-replay-07dcdb22
git -C /tmp/mazewall-tier-e-replay-07dcdb22 checkout --detach 07dcdb22
cd /tmp/mazewall-tier-e-replay-07dcdb22
# Run the command recorded in the historical evidence README here.
```

This is historical reproduction, not a current-source qualification run. Immutable reports, raw artifacts and checksum ledgers are not rewritten. The [Kotlin-migration backlog](../../backlog/issue-20260925-211445-014-move-active-tier-e-experiment-controllers-and-rema.md) now requires replay from a pinned Git revision rather than preserving obsolete executables in HEAD.

## Not in the Tier E deletion batch

- The nine non-Tier-E `scripts/*.py` files need a **separate control-plane/hook inventory**. `pre_invocation_sync.py` and `auto_memory_capture.py` are invoked from `.agents/hooks.json`; `backlog_negative_indexer.py` is invoked from `scripts/git_async_indexer.sh`. `validate_review_evidence.py` is a manual core-evidence checker. Removing any of these would be a different migration.
- Four Python exploit/demo files are called by `scripts/run_vulnerable_app_demo.sh`; deleting them breaks that demo.
- Existing `run_tier_e_*.sh` entrypoints are active compatibility launchers into `scripts/tier_e_tools` and Kotlin, rather than hidden Python/Node controllers. Removing their CLI names is a separate compatibility decision.

## Verification and limits

1. The pinned `07dcdb22` checkout retains the exact bytes of all 28 files. Its standalone Node module/negative fixtures passed **27/27**; its Python PID, source-fingerprint, long-matched and perf-window tests passed **80/80**. Two *separately recorded* legacy coverage-runner tests fail even at the old revision: their fake wrapper lacks the Gradle launcher now required by the Kotlin-owned shell entrypoint. These failures are fixture drift, not a regression introduced by file deletion; they are not reported as green.
2. The active source/caller graph was rechecked across CI, hooks, Gradle, Kotlin and shell scripts. No surviving active caller names a removed Tier E file. Representative Kotlin coverage, split operation, long-matched, PID-reuse and launcher-policy tests passed. No parity claim is made for the retired short hybrid adoption calculator or standalone perf-window analyzer.
3. Immutable historical evidence READMEs, terminals and checksum ledgers were not rewritten. Current guidance uses the pinned Git checkout. Current shell launcher behavior remains Kotlin-owned. Focused Kotlin coverage/split/long-matched/PID/entrypoint tests, full `:profiler:test`, `:profiler:ktlintCheck`, `detektWarningRatchet`, `./scripts/adkw check-backlog` and one `./gradlew build --max-workers=1` all passed after removal.
4. This deletion changes the runtime source fingerprint. Earlier exact-source Tier E acceptance (candidate `07dcdb22`) does **not** transfer to the cleanup commit merely because no Kotlin behavior was intended to change.
