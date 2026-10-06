# Packaged integration consumer baselines

This is an independent Gradle build: no project dependencies, composite builds,
source directories, or repository classpath are injected. Both consumers depend
only on the staged `io.mazewall:enforcer` publication. MazeWall coordinates are
resolved exclusively from the supplied file repository; external dependencies
use Maven Central.

Run from the repository root (replace the version suffix with the source revision
for a new baseline):

```bash
./gradlew --init-script tests/consumers/stage.init.gradle \
  -PconsumerVersion=0.1.0-baseline.95417771 \
  -PconsumerRepository="$PWD/build/consumer-repository" \
  :platform:publishAllPublicationsToConsumerBaselineRepository \
  :enforcer:publishAllPublicationsToConsumerBaselineRepository

./gradlew -p tests/consumers \
  -PconsumerVersion=0.1.0-baseline.95417771 \
  -PconsumerRepository="$PWD/build/consumer-repository" \
  :java-api:run :java-api:verifyCallableAmbiguity :kotlin-api:run

./gradlew -p tests/consumers \
  -PconsumerVersion=0.1.0-baseline.95417771 \
  -PconsumerRepository="$PWD/build/consumer-repository" \
  resolveIntegrationBaselines
```

The staging init script assigns the same pinned version to project dependency
metadata and publications. It adds a local publication destination; the commands
select only that destination. It does not publish to a remote repository or
install artifacts into Maven Local.

The executed actions construct and inspect policies without installation. Callable
examples compile but are not executed. `verifyCallableAmbiguity` compiles an uncast
Java lambda separately and requires the actual javac ambiguity diagnostic. During
the canonical callable migration, replace this characterization with a successful
uncast compile gate.

## Compatibility evidence

- Wrapper: Gradle 9.6.1, reused from the main repository.
- Consumer runtime/toolchain: JDK 25; tested host is Oracle GraalVM
  `25.4.4.1.1+1.1` (HotSpot `25.0.4.1.1+1-LTS-jvmci-25.4-b23`).
- Kotlin consumer/compiler: `2.4.20-Beta1`, matching current producer metadata.
  This is a prerelease-compiler baseline, not evidence of stable-Kotlin consumer
  compatibility. No metadata/prerelease skip flags are used.
- Core resolved versions: coroutines `1.10.2`, serialization JSON `1.9.0`.
- Integration resolution probes: upstream Koog `ai.koog:agents-tools:1.3.0` and
  Spring Boot `4.1.1` BOM with Spring AOP/JDBC. Dependency resolution alone is not
  adapter compilation, advisor-chain, JDBC, or native-protection evidence.
  The Koog graph resolves Kotlin `2.3.10`, coroutines `1.10.2`, and serialization
  `1.10.0`; the Boot BOM resolves Spring `7.0.9`. Koog's serialization version
  differs from core's `1.9.0`; the combined adapter compile/runtime gate remains
  required before accepting that upgrade in an integration module.
- Publication tested: local staged Maven repository under `build/consumer-repository`.
  This does not establish Maven Central or GitHub Packages availability.

Kernel enforcement, stable compiler compatibility, JDK 22 consumer execution,
packaged Portal tools, and Spring application behavior have separate release gates.

Dependency lockfiles pin the resolved consumer and integration-probe graphs.
When intentionally staging another source version, rerun the commands with
`--write-locks` and review the lockfile diff.
