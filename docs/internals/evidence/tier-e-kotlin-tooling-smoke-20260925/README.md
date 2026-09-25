# Kotlin Tier E tooling development smoke (2026-09-25)

Diagnostic only. This is **not** a decision-quality performance run: the
checkout had nine dirty/untracked runtime inputs (`input_dirty_count=9`) and
the long matrix contains only **one** seven-arm fork. No 15-pair confidence
bound or policy input can be derived from it. The retained historical first
and corrected 15-fork decisions remain authoritative, opt-in experimental,
and `policy_eligibility=false`.

`kotlin-tooling-smoke.tar.zst` retains the one-fork rootful long matrix, one
short six-arm hybrid matrix, and a separate 72-path split-privilege smoke.
The long run declares 75,000 operations per arm and completes 7/7 arms, six
profiled with the 72-path oracle. The short hybrid run completes 6/6 arms;
the standalone coverage smoke reports `PASS`, 72 recovered edges and a
`COMPLETE` terminal. Kotlin commands perform fingerprinting, row building,
target/collector CPU sampling, and oracle verification on the active paths.

The initial standalone fixture attempt failed with exit 141 before any
container was created: `nm` received SIGPIPE because an `awk` match exited
early under `pipefail`. The debug trace and failed log are under `commands/`.
The marker lookup was corrected to consume the complete `nm` output, and
the subsequent rootful fixture and matrices completed. Failed inputs are
retained here rather than silently excluded.

The 75,000-operation workload deliberately paces each operation at 800 µs,
imposing a **60-second wall-time floor per arm**. The one-fork run took roughly
494 seconds total (seven arms). Gradle distribution, native SO, and image
reuse were enabled (`TIER_E_SPLIT_REUSE_BUILD=1`); this wall time is not a
repeated build cost. A 72-operation run is the fast feedback path. The long
matrix remains an explicitly invoked Gradle task, not part of `build`.

Archive SHA-256:
`cc5fefc618cd96b91c868126cd56469d810d974405e5dba744d7dba1b262007f`.
`member-sha256-index.txt` checked **202/202** members after an independent
extraction. From this directory:

```bash
sha256sum -c SHA256SUMS
mkdir -p /tmp/tier-e-kotlin-smoke-check
zstd -d -c kotlin-tooling-smoke.tar.zst | tar -xf - -C /tmp/tier-e-kotlin-smoke-check
(cd /tmp/tier-e-kotlin-smoke-check && sha256sum -c "$OLDPWD/member-sha256-index.txt")
```

The JUnit Kotlin acceptance/estimator tests separately check both retained
15-fork archives against their prior metrics and source/row contracts. The
experimental hybrid mode remains disabled by default and the operator's
`REJECT_DEFAULT_PROMOTION` decision stands.
