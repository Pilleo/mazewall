# JDK 25 openat Tier E capture-profile smoke - 2026-09-17

- Profile: `jdk25-unix-openat0`
- Run: rootful Docker, CPU-pinned, one fork, zero warm-ups, one measured batch.
- Artifact: `build/tier-e-profile-smoke-20260917/results.jsonl`
- Source: `3ef5e0ad` plus the profile implementation commits.

## Verdict

The harness terminal record is successful. Each benchmark variant reported
`workload_correctness=PASS` and complete whole-session evidence. USER_NOTIF
reported `diagnostic_dropped=0` for the saved oracle workload.

This is an admission and declared-domain correctness smoke only. It is not a
performance comparison: a one-fork, one-batch sample is too small to support a
speed claim. The profile remains benchmark-only, JDK-25-qualified, and
fail-closed on version mismatch.
