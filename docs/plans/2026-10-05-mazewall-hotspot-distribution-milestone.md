# HotSpot distribution and installed example — milestone 7

The optional `:profiler:distHotSpotAssets` task creates a local versioned
Linux-amd64 archive from verified native inputs; it is not a JVM build/runtime
prerequisite and does not publish remotely. Pairing:
profiler0.1.0-SNAPSHOT / collector2 / captureABI2 / wire2.

Contents: collector, reachable local OCI graph, checksummed provenance, actual
build information, notices for 166 linked Go modules, Go/MazeWall license text,
and corresponding canonical collector/overlay plus both actual patched upstream
source trees. GPL-2.0 BPF notices are separate from Apache-2.0 userspace notices.
Sorted paths/fixed gzip/tar metadata are deterministic; trusted checksums are not
signatures or a license inventory for separately supplied JVM dependencies.

Verification:

- Five distribution tests pass: changed notice, missing/unlisted file (including
  a nested checksum-named file),
  symlink/path traversal refusal and deterministic archive bytes.
- Five asset tests pass, including transitive BPF graph, atomic installation,
  corruption/version/required-layer and symbolic-helper refusal.
- Actual Gradle native distribution packaging succeeds.
- Extracted archive plus copied installed `lib/` runs the compiled public
  `HotSpotInstalledExample` from `/tmp/mw-installed-example-qc0mt5nv`, with no
  experiment class directory/compiler/collector registry access. Ordinary target
  UID1000 in the pinned Temurin interpreter/SerialGC JVM returns `workload-value`,
  retains 60 records with useful own frames, checks physical drain and reports
  incomplete coverage. Helper authorization is an explicit operator rootful
  container prefix, not inferred from the target's privileges.
- The same installed example with normal JIT startup exits1 with
  `unsupported HotSpot identity profile: need pinned Temurin interpreter-only
  Serial GC`; no result success file is created.
- `HotSpotAssetsTest`, Kotlin/legacy ABI gates, `./gradlew build`,
  `./scripts/lint.sh` and backlog validation pass. Library ABI remains unchanged;
  the new entrypoint lives in the tooling source set.

Guidance/design/maps now record version/JDK/tested-kernel pairing, installation,
license/source preservation, operator privilege/control requirements, process-wide
background scope, cold availability, handler-entry semantics and partial coverage.
Measured interpreter admission/dense-readout limitations remain an open follow-up.
Clean installation/drain do not change policy eligibility or runtime guarantees.
Kubescape dependency/storage integration remains independent.

Final local development archive SHA-256:
`558054331f295a42fd0b6a0f26965aedd742b574d2b2c814f3eeb6a18d14d3be`.
The corrected nested-checksum filename exemption has an observed failing test
followed by all five distribution tests passing. Final source/guidance archive
was regenerated after that correction; runtime collector/OCI fingerprints are
unchanged from the accepted installed example.
