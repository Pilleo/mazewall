# Mazewall Real-World CVE Exploitation Demo

This subproject demonstrates `mazewall`'s kernel-level security guarantees by running automated attacks against a Spring Boot application that has inherited real-world vulnerabilities from its dependencies.

The canonical [startup and evidence lifecycle diagram](../../docs/diagrams/vulnerable_web_lifecycle.svg)
shows the single protection decision and shutdown ordering.

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
bootstrap, conditional service beans, embedded HTTP server, benign admin endpoint, vulnerable
command endpoint, orderly context shutdown, and profile publication. It proves that the
unprotected command succeeds without overwriting a pre-existing fixture, that the protected
command is denied while benign HTTP work still succeeds, and that the profiling instance
publishes non-empty evidence. Tests create private temporary roots and remove only files they own.

---

## Manual Execution (Step-by-Step)

If you prefer to perform each step manually:

### 1. Build the Application
```bash
./gradlew :demos:vulnerable-web-app:bootJar
```

### 2. Start the Environment
This starts three loopback-only instances of the same application:
- **Unprotected** (`http://localhost:8082`): Standard JVM, no Mazewall filters.
- **Protected** (`http://localhost:8081`): Scoped Mazewall filters applied to vulnerable threads.
- **Profile** (`http://localhost:8083`): observation mode; writes the final SBoB on orderly shutdown.

```bash
podman compose -f demos/vulnerable-web-app/compose.yml up -d
```

### 3. Exercise the HTTP boundary

The packaged app exposes a deliberately unsafe command route for the executable comparison. Use a
disposable container instance; do not expose this application to an untrusted network.

```bash
# Benign control through a protected service executor
curl -fsS -H 'X-Api-Version: 1.0' http://127.0.0.1:8081/admin/log

# The /command comparison is covered by the isolated integration test above.
# It succeeds on :8082 and is expected to fail on :8081.
```

The application itself defaults `server.address` to `127.0.0.1`, and all compose host bindings
also use `127.0.0.1`; the deliberately vulnerable services are not exposed on every host interface.

### Profiling lifecycle

`MAZEWALL_ENABLED` is resolved once by Spring's prepared environment before the application
context creates worker pools. The resolved value is then published as
`mazewall.resolved-enabled`; both the process-wide baseline and protected/unprotected bean
selection consume that one decision.

Profiling is intentionally a separate instance from enforcement. On shutdown it first rejects
new service work, then drains all accepted service tasks against one bounded deadline, and only
then snapshots profiler evidence. Each publication writes the SBoB, stack dictionary, and
digest-backed integrity record into one immutable generation directory. Only after all three
components exist does one atomic replacement make `sbob.json` point to that generation. Readers
resolve and validate that pointer, so an interrupted publication cannot combine components from
different generations or advertise an incomplete generation as current. The integrity record
contains `complete`, every drain/cleanup failure reason, and SHA-256 digests for both evidence
components; timeout and interruption are never overwritten by a later cleanup failure. Legacy
direct SBoB files remain readable by enforcement. Configure the drain deadline with
`-Dmazewall.executor-drain-seconds=N` (`1..300`, default `10`).

## How It Works

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
