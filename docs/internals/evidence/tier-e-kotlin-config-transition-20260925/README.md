# Typed Tier E config and Kotlin helper transition (2026-09-25)

**In progress, source-dirty diagnostic only.** This does not establish a
Kotlin-only active execution path, an application-overhead decision, a
75,000-operation performance run, or Podman runtime parity. Hybrid remains
opt-in, `policy_eligibility=false`, and `REJECT_DEFAULT_PROMOTION` stands.

Versioned profiles in `config/testing/tier-e/` now feed the short rootful
fixture through a profiler-local typed loader. The generic root test endpoint
condition no longer reads Tier E-specific engine/URI names. The selected
profile and legacy override compatibility are validated before the fixture
starts, and its resolved JSON is saved alongside the run artifacts. Both
container creation requests now check a lock-protected `open`/`closed`
admission state: teardown closes admission under the lock before reconciling
owned names. A test confirms post-closure creation is refused. A deliberately
interrupted controller wait restores the thread's interrupt status after
sentinel cleanup.

The active split fixture has no direct Node/Python process invocations after
moving its negative binary envelope, selected fault/negative/normal verdicts,
and bounded registered-host-PID probe to Kotlin tooling. The bounded PID
probe has host unit coverage, but **real guest PID reuse was not rerun** in
this slice. A 72-path real rootful ring-drop fixture passed with coverage
`PASS` and terminal `INCOMPLETE`; a real unclosed-artifact negative fixture
passed with Kotlin envelope construction and negative verdict. The short
matched and live operation verifier and short matched fork row/completeness
parser now call Kotlin. On the untouched
`docs/internals/evidence/tier-e-split-matched-20260922/raw-five-fork.tar.zst`
archive, Kotlin matched Node's per-fork JSON and exit status for **35/35**
arms; `short-row-parity.json` binds the archive digest. The active matched,
fault and live source-fingerprint callers now invoke Kotlin, and Python and
Kotlin digests/inventories matched on the same checkout.

`rootful-docker-junit.xml` records **8/8** rootful Docker cases after a
successful explicit-rootful `./gradlew build` (264 tasks). The first build
attempt found a test-only fixed sleep forbidden by the repository's
synchronization policy, and an unrelated `/proc/<pid>/fd` permission race;
the fixed sleep was replaced by a blocked latch, the focused enforcer tests
passed, and the full build then passed. `absent-podman-junit.xml` records
**8/8 skips** with an absent Podman executable reason. Shell syntax and
profiler unit tests passed. The historical archives and their ledgers were
not modified.

**Remaining active work:** substantial Bash split fixture and benchmark
controllers, inline Node live/fault matrix row and summary analysis,
capacity/rootful benchmark ownership, and CLI/Gradle migration to the same
typed profile are still open. The explicit long benchmark remains Bash-owned
and must not be mistaken for Kotlin-only. See the
[migration design](../../designs/profiler/tier-e-testing-migration.md) and
the open scoped backlog item. `SHA256SUMS` covers the four retained receipts
besides itself.
