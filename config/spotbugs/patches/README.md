# Analyzer-only SpotBugs corrections

These patches modify **SpotBugs 4.10.5**, not MazeWall runtime artifacts.
Upstream: <https://github.com/spotbugs/spotbugs/tree/4.10.5>.
The existing Gradle plugin remains 6.5.5; FindSecBugs is 1.14.0.

Source coordinate: `com.github.spotbugs:spotbugs:4.10.5:sources@jar`.
SHA-256: `463c56c7190a0c27abbefacc08637eb575bc1b369ed892f009521cbc91864a79`.
Gradle's reviewed verification metadata also pins resolved compiler/tool artifacts.

Each properties file records an exact source path, preimage/output SHA-256 and
unique replacement fragments. `generateSpotBugsSources` rejects drift before
compilation. `spotbugsPatchJar` includes the modified source, patches, provenance
and upstream license; it precedes official core only on the analyzer JVM classpath.
Nothing is added to application dependencies, publications or detector filters.

Modified 2026-10-19: four upstream analyses now check whether value-number facts
are valid before reading them. An invalid TOP/BOTTOM frame cannot supply a value
identity or range constraint. Reachable range contradictions, impossible casts,
nonnull-argument violations and useless allocations remain analyzed.

Upstream source headers retain their copyrights and LGPL-2.1-or-later terms.
The overlay includes the unmodified license document from the verified upstream
core JAR. Reconstruct with `./gradlew spotbugsPatchJar`; inspect
`build/tools/spotbugs/mazewall-spotbugs-tool-only.jar`.

Every SpotBugs task additionally requires a valid XML report with zero analysis
errors and missing classes, after waiting for its asynchronous worker. The
Gradle plugin's process exit handling alone does not enforce this condition.

The coroutine placeholder diagnostic is **not** exempted by these patches.
