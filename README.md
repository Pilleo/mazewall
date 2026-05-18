# contained-executors

**Kernel-enforced containment for Java threads. No agents. No SecurityManager. Just seccomp.**

---

## The Solution

Wrap the executor that runs untrusted code. The kernel enforces the policy — no JVM bytecode can circumvent it.

```kotlin
val safe = ContainedExecutors.wrap(
    Executors.newSingleThreadExecutor(),
    Policy.NO_EXEC
)

// The exploit payload reaches the vulnerable logger...
val future = safe.submit { vulnerableLogger.log(maliciousInput) }

// ...but the kernel intercepts execve() and returns EPERM.
// The reverse shell never spawns. Attack neutralized.
future.get() // throws ExecutionException { cause: ContainmentViolationException }
```

## How It Works

`contained-executors` uses **Linux seccomp-bpf** to install system call filters. The implementation is 100% pure Java, using the Foreign Function & Memory (FFM) API to interact with the kernel.

Prohibited syscalls trigger a `SECCOMP_RET_ERRNO` with `EPERM`, causing standard Java IO/NIO methods to throw exceptions (like `AccessDeniedException`). The executor wrapper catches these to wrap them in a `ContainmentViolationException`.

## Quick Start

### Try It Live (No Setup Required)
- **[Interactive Playground on Killercoda](https://killercoda.com/YOUR_USERNAME/scenario/jsecomp)** – A free, browser-based Linux environment.
- **[Watch the Demo](#)** – 30-second terminal recording.

### 2. Configure a Thread Pool with File Restrinctions (Landlock)

Modern Linux kernels support [Landlock LSM](https://landlock.io/), which allows unprivileged processes to sandbox their own filesystem access. `jseccomp` automatically applies Landlock rules alongside Seccomp:

```kotlin
// Allow processing files in /data/incoming, but strictly block network and execution
val policy = Policy.builder()
    .base(Policy.PURE_COMPUTE)
    .allowJvmClasspath() // Crucial: allow lazy loading of JVM classes
    .allowFsRead("/data/incoming")
    .allowFsWrite("/data/processed")
    .build()

val executor = ContainedExecutors.wrap(
    Executors.newFixedThreadPool(4),
    policy
)

executor.submit {
    // This will work:
    val data = File("/data/incoming/task1.json").readText()
    File("/data/processed/result.json").writeText(data)
    
    // This will throw AccessDeniedException:
    File("/etc/passwd").readText()
}
```

### 3. Graceful Degradation

### Local Development
- **Linux** (x86_64 or aarch64)
- **JDK 22+** (requires FFM API)
- **Docker:** `docker compose up -d && docker compose exec jseccomp ./gradlew test`
- **Podman:** `podman run --security-opt seccomp=unconfined -it --rm -v $(pwd):/app:Z -w /app fedora:40 ./gradlew test`

> **Note:** Containers must run with `seccomp=unconfined` because the project applies its own nested filters.

## Usage

**Wrap a thread pool:**
```kotlin
val executor = ContainedExecutors.wrap(
    Executors.newFixedThreadPool(4),
    Policy.NO_EXEC
)
```

**Global process lockdown:**
```kotlin
ContainedExecutors.installOnProcess(Policy.NO_NETWORK)
```

**Built-in policies:**

| Policy                | Blocked syscalls                            |
|-----------------------|---------------------------------------------|
| `Policy.NO_EXEC`      | `execve`, `fork`, etc.                      |
| `Policy.NO_NETWORK`   | `connect`, `socket`, `bind`, etc.           |
| `Policy.PURE_COMPUTE` | All of the above + `open`, `ioctl`, `prctl` |

> **Important:** Seccomp filters are permanent. Never share a contained thread pool with uncontained tasks. See the **Javadocs in `ContainedExecutors`** for critical details on thread pool poisoning and virtual thread limitations.

## Building

```bash
./gradlew build
```

| Module  | Purpose                                    |
|---------|--------------------------------------------|
| `utils` | The `io.contained` library                 |
| `demo`  | Log4Shell exploit/protection demonstration |
