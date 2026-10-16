# Gradle-native portable artifact cache and matched workload timing — 2026-09-27

**Build-cache contract:** Two independent, clean detached checkouts at
`47597329` used the same OpenJDK 25, rootful Docker image digest and private
Gradle build cache. Checkout A's explicit `:profiler:tierENativeBuild` built
in **24 seconds**; checkout B restored its native artifact package
`FROM-CACHE` in **6 seconds**. The byte-for-byte equal package has **23**
hashed files (agent library, 14 executable native test fixtures, workload
classes and redefinition JAR). Executable modes survived restoration.
The restored package passed **39/39 freshly run native CTests** in a fresh
writable CMake directory; no CTest result was part of the cached package.
The container-native source mount was read-only and both checkouts stayed
clean. The absolute CMake output path inside Docker was stable, independent
of each host checkout path.

**Test limitation:** The host's tracked Gradle 9.6.1 wrapper intermittently
cannot load `GradleWorkerMain` from the buildSrc TestKit child worker when run
directly on this host. Only this clean-checkout cache proof excluded
`:buildSrc:verificationActionsTest` to bypass that *upstream worker startup*
failure. BuildSrc's functional replay/native cache tests (including changed
native source, header, Java workload, image, compiler, JDK, CMake,
architecture, build type, USDT option, and foreign-file preservation) passed
independently with the current host Gradle wrapper. This is a limitation on
the clean-checkout cache probe, not evidence that skipped tests passed.

**Matched end-to-end diagnostic:** Two fresh, one-fork/seven-variant
trustworthy runs used identical source fingerprint, image ID and agent SHA,
with all seven fork outputs exact and `policy_eligibility=false`. The first
took **426.44s** wall; the second **272.47s**. Bundle-native configure/build
was **0 ms in both**, because each consumed the Gradle native package. The
bundle's own measured Gradle runtime-install stage was **171,916 ms**, then
**0 ms** on reuse. Remaining wall time includes bootstrap Gradle and fresh
benchmark execution; neither is a cache hit or a qualified performance
claim. These matched runs were from an intermediate source-dirty development
worktree, not a clean release matrix. They neither change the historical
phase baseline nor promote a capacity ceiling.

`manifest.json` records native package source/image identity and both complete
terminals, bundle stage timings and wall times. `raw-receipts.tar.gz` contains
the two package manifests, Gradle task logs, restored-package native CTest
transcript, both fork JSONLs, bundle manifests and bounded build logs.
`SHA256SUMS` seals these small receipts; the compiled binaries themselves
remain in the independent host-local Gradle build caches.
