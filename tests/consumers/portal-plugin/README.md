# Published Portal plugin regression

This independent build applies the real `io.mazewall.portal.codegen` Maven
plugin marker and stable Kotlin **2.4.20**. It generates and compiles both a host
stub and a dedicated worker dispatcher for the small Java consumer contract.
MazeWall dependencies resolve exclusively from the supplied Maven repository;
there is no plugin-classpath injection, included build or source substitution.

The worker publication contains the existing bootstrap endpoint required by
the codegen dependency closure. It does not enable guest construction in the
host, a path fallback, or a broker/worker boundary change.

Run a copied fixture in an evidence directory so new buildscript/dependency
locks and outputs do not overwrite historical consumer locks. Supply these
properties to the monorepo Gradle wrapper with `-p <copied-fixture>`:

- `consumerRepository`: absolute repository containing matching platform,
  enforcer, profiler, runtime, worker, codegen and plugin marker publications.
- `consumerVersion`: the exact version of every MazeWall artifact.
- `consumerEvidence`: absolute directory for compiled outputs and `graph.json`.

Under the shared verification lock, use a finite command budget and JDK25:
first `clean verifyPublishedPlugin --write-locks`, then
`clean verifyPublishedPlugin` without lock rewriting. The task records actual
plugin/worker defining JARs and dependency hashes, requires repository-local
MazeWall artifacts, and checks generated host/worker classes. Compare both
graphs and retain the unchanged lockfiles. No native guest is launched.

Against the historical c049 repository the actual plugin request must fail
with missing `io.mazewall:worker:0.1.0-candidate.c049dd3c`. A successful
checking-coordinate run is a publication regression check, not final-candidate
or native containment acceptance. Task6d1's controller-owned evidence records
the exact source hash, commands, publication metadata and physical end.
