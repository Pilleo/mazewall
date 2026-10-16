---
title: "Investigate worker liveness after SandboxDispatcher shutdown"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: [":enforcer"]
target_files:
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/ContainedExecutorWrapTest.kt"
target_symbols:
  - "SandboxDispatcher.shutdownAll"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Investigate worker liveness after SandboxDispatcher shutdown

**Context:**
Local-only benchmark cleanup verification hit the unchanged ContainedExecutorWrapTest worker.isAlive assertion at line 37 after shutdownAll, in full build run-1c158fea889c and focused run-12149dbfe9f5. SandboxDispatcher and ContainedExecutorWrapper production sources have no cleanup diff. shutdownAll delegates to executor awaitTermination; whether this is a worker-exit observation race or incomplete retirement remains unproven. Preserve the assertion and containment behavior while investigating.

**Needed:**
1. Reproduce with worker state and executor termination evidence; establish the physical thread-retirement contract before changing either production shutdown or the test.

## Established cause and correction

The instrumented original assertion reproduces the failure in `run-63b2a11fc681`:
the cached executor is terminated while the actual worker remains `RUNNABLE` in
`Thread.exit` → `TerminatingThreadLocal.threadTerminated`, doing JaCoCo-instrumented
class loading. A real join observes thread death 42,839 ns later. The diagnostic
still asserts the original immediate liveness snapshot, so it remains RED.

The installed JDK25 source confirms that `ThreadPoolExecutor.processWorkerExit`
publishes pool termination before the enclosing platform thread finishes JVM
exit hooks (`run-d232336756cd`). `OwnedDomain.awaitRetirement` already treats
executor termination and physical thread joining as separate observations.

The test now joins its acquired platform worker with a five-second bound before
asserting that it is dead. A stalled worker still fails. This is a test-observation
race; production dispatcher shutdown, containment installation and error behavior
are unchanged.

Focused formatting and all19 dispatcher/coroutine/required-installation checks
pass with zero failures/errors/skips (`run-2e7aa71aa85b`, `run-cce47f8f91c3`).
The full included-module `./gradlew build --console=plain --max-workers=1` passes
at `run-74490f4ff1a6` (744,555 ms). The first completion attempt correctly rejected
this newly created issue's missing index row; its active membership/count was
corrected before the successful build. The issue is archived only after that gate.

---
<!-- id: issue-20261015-094330-565-investigate-worker-liveness-after-sandboxdispatche  file: issue-20261015-094330-565-investigate-worker-liveness-after-sandboxdispatche.md -->
