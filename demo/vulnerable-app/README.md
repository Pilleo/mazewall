# Mazewall Real-World CVE Exploitation Demo

This subproject demonstrates `mazewall`'s kernel-level security guarantees by running automated attacks against a Spring Boot application that has inherited real-world vulnerabilities from its dependencies.

## Vulnerability Matrix

| Attack Vector | Vulnerability | CVE / Link | Impact | Mazewall Protection |
|---------------|---------------|------------|--------|---------------------|
| **Log4Shell** | Log4j 2.14.1 | [CVE-2021-44228](https://nvd.nist.gov/vuln/detail/CVE-2021-44228) | Remote Code Execution (RCE) | `Policy.NO_NETWORK` |
| **SnakeYAML** | SnakeYAML 2.3 | [CVE-2022-1471](https://nvd.nist.gov/vuln/detail/CVE-2022-1471) | Deserialization RCE | `Policy.NO_EXEC` |
| **XStream**   | XStream 1.4.17 | [CVE-2021-39144](https://nvd.nist.gov/vuln/detail/CVE-2021-39144) | Deserialization RCE | `Policy.NO_EXEC` |
| **XXE**       | DocumentBuilder | [XXE Injection](https://owasp.org/www-community/vulnerabilities/XML_External_Entity_(XXE)_Processing) | File Exfiltration | Landlock Path Restriction |
| **SSRF**      | RestTemplate | [SSRF](https://owasp.org/www-community/attacks/Server_Side_Request_Forgery) | Internal Network Scanning | `Policy.NO_NETWORK` |
| **SSTI**      | Thymeleaf 3.x | [SSTI RCE](https://www.veracode.com/blog/secure-development/spring-view-manipulation-vulnerability) | Template Manipulation RCE | `Policy.NO_EXEC` |
| **Zip Slip**  | ZipInputStream | [Zip Slip](https://snyk.io/research/zip-slip-vulnerability) | Arbitrary File Write | Landlock Path Restriction |
| **SQLi**      | JdbcTemplate | [SQL Injection](https://owasp.org/www-community/attacks/SQL_Injection) | Database Compromise | *Out of Scope (Data-plane)* |

## Prerequisites

- **Linux Kernel 6.2+** (required for Landlock support)
- **Podman** (preferred) or Docker with `compose` support
- **JDK 22+** (if building locally)

## Quick Start

### 1. Build the Application
```bash
./gradlew :demo:vulnerable-app:bootJar
```

### 2. Start the Environment
This starts two instances of the same application:
- **Unprotected** (`http://localhost:8082`): Standard JVM, no Mazewall filters.
- **Protected** (`http://localhost:8081`): Scoped Mazewall filters applied to vulnerable threads.

```bash
podman compose -f demo/vulnerable-app/compose.yml up -d
```

### 3. Run Automated Exploits
Run the python exploit suite to see Mazewall in action:

```bash
# Run exploits against the unprotected instance (Expected: All succeed)
python3 exploits/run_all.py http://localhost:8082

# Run exploits against the protected instance (Expected: All RCE/IO blocked)
python3 exploits/run_all.py http://localhost:8081
```

### 4. Verify Functionality
Ensure that Mazewall hasn't broken the legitimate features of the application:

```bash
python3 exploits/health_check.py http://localhost:8081
```

### 5. Generate Comparison Report
Capture results into JSON and generate a Markdown summary:

```bash
python3 exploits/run_all.py http://localhost:8082 > unprotected.json
python3 exploits/run_all.py http://localhost:8081 > protected.json
python3 exploits/verify_results.py unprotected.json protected.json demo/vulnerable-app/report.md
```

## How It Works

The demo uses **Scoped Bean Protection**. Instead of locking down the entire Spring Boot process (which would break the embedded Tomcat), we wrap specific `ExecutorService` beans that handle untrusted parsing:

```kotlin
@Bean
fun importExecutor(): ExecutorService =
    ContainedExecutors.wrap(
        Executors.newFixedThreadPool(4),
        Policy.builder()
            .base(Policy.NO_NETWORK)
            .allowMmapExec()
            .allowFsRead("/app/data") // Landlock restriction
            .allowJvmClasspath()
            .build()
    )
```

In the controllers, we simply submit the vulnerable task to the protected executor:

```kotlin
@PostMapping("/import/yaml")
fun importYaml(@RequestBody body: String): String {
    return yamlImportService.import(body) // This runs inside the sandbox
}
```

When a malicious payload (like a SnakeYAML RCE gadget) attempts to call `Runtime.exec()`, the kernel intercepts the `execve` system call and returns `EPERM`. Mazewall catches this, logs the violation, and ensures the JVM thread remains stable.
