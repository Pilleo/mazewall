# Mazewall Real-World CVE Exploitation Demo

This subproject demonstrates `mazewall`'s kernel-level security guarantees by running automated attacks against a Spring Boot application that has inherited real-world vulnerabilities from its dependencies.

The canonical [startup and evidence lifecycle diagram](../../docs/diagrams/vulnerable_web_lifecycle.svg)
shows the single protection decision and shutdown ordering.

The normal application and compose image omit the executable `CustomGadget` and every
deserialization import/payload HTTP route. These attack fixtures are compiled into a separate
artifact only by `:demos:vulnerable-web-app:isolatedVulnerableFixtureJar`; its routes additionally
require the Spring profile `isolated-vulnerable-fixture`. Run that artifact only in an isolated,
disposable environment. It is deliberately absent from the default compose services.
The checked-in `results_report.md` records only fixture packaging/profile verification with its
exact provenance; it is not evidence that the standard application was attacked.

## Vulnerability Matrix

| Attack Vector | Vulnerability | CVE / Link | Impact | Mazewall Protection (Standard JVM) |
|---------------|---------------|------------|--------|---------------------|
| **Log4Shell** | Log4j 2.14.1 | [CVE-2021-44228](https://nvd.nist.gov/vuln/detail/CVE-2021-44228) | RCE (Data-plane) | `Policy.NO_NETWORK` (Trapped) |
| **SnakeYAML** | SnakeYAML 2.3 | [CVE-2022-1471](https://nvd.nist.gov/vuln/detail/CVE-2022-1471) | Deserialization RCE | Tier 1 `NO_EXEC` (Shell blocked, exfiltration possible via Thread-Hopping) |
| **XStream**   | XStream 1.4.17 | [CVE-2021-39144](https://nvd.nist.gov/vuln/detail/CVE-2021-39144) | Deserialization RCE | Tier 1 `NO_EXEC` (Shell blocked, exfiltration possible via Thread-Hopping) |
| **XXE**       | DocumentBuilder | [XXE Injection](https://owasp.org/www-community/vulnerabilities/XML_External_Entity_(XXE)_Processing) | File Exfiltration | Landlock Path Restriction (Trapped) |
| **SSRF**      | RestTemplate | [SSRF](https://owasp.org/www-community/attacks/Server_Side_Request_Forgery) | Internal Network Scanning | `Policy.NO_NETWORK` (Trapped) |
| **SSTI**      | Thymeleaf 3.x | [SSTI RCE](https://www.veracode.com/blog/secure-development/spring-view-manipulation-vulnerability) | Template Manipulation RCE | Tier 1 `NO_EXEC` (Shell blocked, exfiltration possible via Thread-Hopping) |
| **Zip Slip**  | ZipInputStream | [Zip Slip](https://snyk.io/research/zip-slip-vulnerability) | Arbitrary File Write | Landlock Path Restriction (Trapped) |
| **SQLi**      | JdbcTemplate | [SQL Injection](https://owasp.org/www-community/attacks/SQL_Injection) | Database Compromise | *Out of Scope (Data-plane)* |

### ⚠️ The "Thread-Hopping" Exfiltration Caveat
Notice the distinction in the matrix above. For **Untrusted Data** attacks (SSRF, XXE, Log4Shell's initial JNDI lookup), Mazewall can intercept the relevant synchronous system calls when they execute on the scoped worker thread.

However, for attacks that grant **Arbitrary Code Execution (Java logic)** such as SnakeYAML or Thymeleaf SSTI, the attacker can use standard Java concurrency APIs like `CompletableFuture.runAsync(...)` to execute their payload on the global `ForkJoinPool`. 

Because those global threads were spawned at startup, they only inherited the process-wide **Tier 1 (`NO_EXEC`)** policy. 
1. **The Attacker CANNOT spawn a shell** (Blocked by Tier 1).
2. **The Attacker CAN exfiltrate data** by reading files or opening network sockets (Bypassing Tier 2).

This demonstrates that on a standard JIT JVM, thread-scoped containment is a highly effective shield for trusted code, but it is not a complete cage for malicious code. For absolute isolation of untrusted logic, **GraalVM Isolates** or **Wasm** are required.

## Prerequisites

- **Linux Kernel 6.2+** (required for Landlock support)
- **Podman** (preferred) or Docker with `compose` support
- **JDK 22+** (if building locally)

## Quick Start

The easiest way to execute the entire real-world CVE exploitation demo is to run the integration test via Gradle:

```bash
# Execute the complete automated exploitation and verification
./gradlew :demos:vulnerable-web-app:test --tests '*VulnerableWebAppFreshJvmTest'
```

The test starts the real Spring application in separate JVMs. It exercises the environment
bootstrap, conditional service beans, embedded HTTP server, existing benign admin endpoint,
service-bean execution boundary, orderly context shutdown, and profile publication. It proves
that an unprotected command succeeds without overwriting a pre-existing fixture, that the same
operation is denied by the protected bean while benign HTTP work still succeeds, and that the
profiling instance publishes non-empty evidence. The test also asserts that no `/command` HTTP
route exists. Tests create private temporary roots and remove only files they own.

---

## Manual Execution (Step-by-Step)

If you prefer to perform each step manually:

### 1. Build the Application
```bash
./gradlew :demos:vulnerable-web-app:bootJar
```

### 2. Start the Environment
The compose file defines three instances with host-loopback port publishing:
- **Unprotected** (`http://localhost:8082`): Standard JVM, no Mazewall filters.
- **Protected** (`http://localhost:8081`): Scoped Mazewall filters applied to vulnerable threads.
- **Profile** (`http://localhost:8083`): observation mode; writes the final SBoB on orderly shutdown.

```bash
podman compose -f demos/vulnerable-web-app/compose.yml up -d profile unprotected
# Exercise the profiling instance, then stop it to publish the generation.
podman compose -f demos/vulnerable-web-app/compose.yml stop profile
podman compose -f demos/vulnerable-web-app/compose.yml up -d protected
```

### 3. Exercise the existing HTTP boundary

Use a disposable container instance; do not expose this deliberately vulnerable application to an
untrusted network.

```bash
# Benign control through a protected service executor
curl -fsS -H 'X-Api-Version: 1.0' http://127.0.0.1:8081/admin/log
```

Direct local runs default `server.address` to `127.0.0.1`. Compose overrides the address inside
each container to `0.0.0.0` so Docker/Podman port forwarding can reach Tomcat, while every
published host port remains bound to `127.0.0.1`; the deliberately vulnerable services are not
exposed on every host interface. Each compose service also has a loopback health check.

### Profiling lifecycle

`MAZEWALL_ENABLED` is resolved once by Spring's prepared environment before the application
context creates worker pools. The resolved value is then published as
`mazewall.resolved-enabled`; both the process-wide baseline and protected/unprotected bean
selection consume that one decision.

Profiling writes `/app/profiles/sbob.json` and its generation directory into the named
`profile-artifacts` volume. Enforcement mounts the same volume read-only and consumes that exact
path. Start enforcement only after an orderly profiling shutdown has published evidence;
missing evidence aborts startup instead of silently selecting a static policy. The named volume
persists across container recreation; `compose down -v` explicitly removes the recorded evidence.

Profiling is intentionally a separate instance from enforcement. On shutdown it first rejects
new service work, then drains all accepted service tasks against one bounded deadline, and only
then snapshots profiler evidence. Each publication writes the SBoB, stack dictionary, and
digest-backed integrity record into one immutable generation directory. Only after all three
components exist does one atomic replacement make `sbob.json` point to that generation. Readers
resolve and validate that pointer, so an interrupted publication cannot combine components from
different generations. Incomplete generations remain readable for diagnostics but enforcement
rejects them by default. The integrity record
contains `complete`, every drain/cleanup failure reason, and SHA-256 digests for both evidence
components; timeout and interruption are never overwritten by a later cleanup failure. Legacy
direct SBoB files remain readable by enforcement. Configure the drain deadline with
`-Dmazewall.executor-drain-seconds=N` (`1..300`, default `10`).

Generation completeness also requires every registered profiler collector to report complete
coverage. The manager retains each wrapper's immutable behavior/coverage snapshot after profiler
shutdown; dropped events, unresolved paths, collector-drain failures and collector warnings are
retained in the integrity record. Clean executor shutdown cannot erase collection loss. Older
generations containing only a drain-based `complete` marker must be regenerated because they do
not supply collector evidence.

The unsafe override `-Dmazewall.allow-incomplete-profile-enforcement=true` permits enforcement
from incomplete evidence only when an operator explicitly accepts the missing-observation risk.
Profiling and enforcement are mutually exclusive startup modes: setting both
`mazewall.profile=true` and `mazewall.enabled=true` aborts startup.

The container image runs as the unprivileged `mazewall` user. Only `/app/data` and `/app/uploads`
are owned by that user; the named-volume mount point `/app/profiles` remains root-owned but is
group-writable by `mazewall`. The application and exploded classpath remain root-owned and read-only.
Creating those writable roots before startup keeps Landlock rules anchored to the exact
directories instead of falling back to the broader `/app` parent.

## How It Works

To build the optional attack artifact explicitly:

```bash
./gradlew :demos:vulnerable-web-app:isolatedVulnerableFixtureJar
# Inside an isolated disposable environment only:
java -jar demos/vulnerable-web-app/build/libs/isolated-vulnerable-fixture.jar \
  --spring.profiles.active=isolated-vulnerable-fixture
```

The standard `bootJar` remains `vulnerable-app.jar` and cannot load the custom command gadget.
The fixture profile exposes unsafe deserialization routes and a payload generator; it is an
attack laboratory, not a supported deployment mode or proof of an arbitrary-code boundary.

The demo uses **Scoped Bean Protection**. Instead of locking down the entire Spring Boot process (which would break the embedded Tomcat), we wrap specific `ExecutorService` beans that handle untrusted parsing:

```kotlin
val executor = executorOwner.own(
    ContainedExecutors.wrap(
        Executors.newFixedThreadPool(4),
        servicePolicy,
    ),
)
```

In the controllers, we simply submit the vulnerable task to the protected executor:

```kotlin
override fun importYaml(body: String) = executor.call {
    realService.importYaml(body)
}
```

When a malicious payload (like a SnakeYAML RCE gadget) attempts to call `Runtime.exec()`, the kernel intercepts the `execve` system call and returns `EPERM`. Mazewall catches this, logs the violation, and ensures the JVM thread remains stable.

## Future Improvements

As the core `mazewall:enforcer` library matures (specifically addressing the `OPENAT` and `PROT_EXEC` linkage issues logged in the backlog), this demo will be upgraded to demonstrate tighter constraints:

1.  **Stricter Base Policies:** We will transition the executors from `Policy.NO_NETWORK` to `Policy.PURE_COMPUTE` or `Policy.PURE_COMPUTE_UNSAFE`. This will prove that Mazewall can block advanced kernel exploitation primitives (like arbitrary `ioctl`, `mount`, or `prctl` manipulations) beyond just blocking shell execution and network access.
2.  **Simplified Configuration:** The verbose boilerplate (e.g., manually appending `.allowMmapExec()`) will be removed, proving that the built-in presets handle JVM-native linking operations safely by default.
3.  **Lazy Classloading Verification:** A dedicated endpoint will be added to force the JVM to load heavy, uninitialized libraries *inside* the strict sandbox, verifying that `allowJvmClasspath()` seamlessly permits legitimate JVM mechanics while blocking malicious path traversals.
