# Cacheable build, fresh native phase diagnostic — 2026-09-27

The version-two, unchanged historical phase verifier remains fail-closed.
`tierERootfulPhaseProbe` builds its native Debug artifact in a content-keyed
Docker volume and executes **fresh** full/unique CTests in every run. The
separate `tierEReplayPhaseEvidence` task rechecks retained logs; an unchanged
second replay was `UP-TO-DATE`. Gradle configuration cache was reused, and
live kernel integration test tasks were not made cacheable.

Two bounded probes of the same intermediate source image and native agent
completed both full and unique modes. Their owned run IDs and medians differ;
the second native-only probe took 10 seconds (first 23 seconds) while its
native build cache, image digest and agent SHA-256 matched the first. An
earlier probe failed both GetStackTrace limits and retained its stopped
runner and exact volume. Every terminal remained `policy_eligibility=false`.

The selected source was a **dirty intermediate worktree**; these numbers
prove build-output reuse and fresh acquisition only. They are not a
source-pinned interleaved comparison against the historical revision and
cannot qualify a release, change the baseline, or clear the still-failed
final aggregate rootful gate. `manifest.json` records the exact identities;
`raw-samples.tar.gz` contains the terminal, recovery (where applicable),
identity, verdict and all six raw CTest logs per attempt. `SHA256SUMS` seals
the small diagnostic receipts.
