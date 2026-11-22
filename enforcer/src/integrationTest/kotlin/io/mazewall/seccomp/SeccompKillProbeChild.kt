package io.mazewall.seccomp

import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.ProcessPolicyBuilder
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.system.exitProcess

/**
 * Child-process entry point for KILL_* verdict probes (issue-20260823-172000, issue-20261103-235256-949).
 *
 * Runs inside a fresh JVM spawned by [io.mazewall.IsolatedProcessTester]. Installs a seccomp
 * filter on a dedicated platform thread and records explicit file-backed readiness and kernel
 * `/proc/self/task/<tid>` thread-death or survival markers so the parent never mistakes silent exit
 * or pre-install failure for SECCOMP_RET_KILL_THREAD enforcement.
 *
 * Raw POSIX fork() was rejected for this purpose: a forked child of a multithreaded JVM may only
 * execute async-signal-safe code until exec, and installing filters / running probes before exec
 * is undefined behavior with FFM and JIT threads.
 */
object SeccompKillProbeChild {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4) {
            "Expected <victimNr> <mode> <readyPath> <outcomePath>, got ${args.size} args"
        }
        val victimNr = args[0].toInt()
        val mode = args[1]
        val readyPath = Path.of(args[2])
        val outcomePath = Path.of(args[3])

        if (mode == "fail-before-install") {
            exitProcess(2)
        }

        val arch = Arch.current()
        val victimSyscall = Syscall.entries.first { it.numberFor(arch) == victimNr }
        val policy =
            when (mode) {
                "baseline" -> null
                "allow-control" ->
                    ProcessPolicyBuilder
                        .denyList(RuntimeProfile.NATIVE_IMAGE)
                        .defaultAction(SeccompAction.ACT_ALLOW)
                        .allow(victimSyscall)
                        .build()
                "guarded" ->
                    ProcessPolicyBuilder
                        .denyList(RuntimeProfile.NATIVE_IMAGE)
                        .defaultAction(SeccompAction.ACT_ALLOW)
                        .addAction(SeccompAction.ACT_KILL_THREAD, victimSyscall)
                        .build()
                else -> error("Unknown kill-probe mode: $mode")
            }

        val readyLatch = CountDownLatch(1)
        val probeTid = AtomicInteger(-1)
        val survived = AtomicBoolean(false)
        val workerFailure = AtomicReference<Throwable?>(null)

        val probeThread =
            Thread
                .ofPlatform()
                .name("kill-thread-probe")
                .daemon(true)
                .start {
                    try {
                        val tid = LinuxNative.process.gettid().value
                        check(tid > 0) { "Expected positive tid, got $tid" }
                        val taskDir = Path.of("/proc/self/task/$tid")
                        check(Files.exists(taskDir)) { "Expected procfs task directory $taskDir" }
                        probeTid.set(tid)

                        if (policy != null) {
                            // Thread-scoped, non-supervised: no daemon involvement.
                            ContainedExecutors.installOnCurrentThread(policy)
                            val pid = ProcessHandle.current().pid()
                            check(pid > 0) { "Liveness probe failed after install" }
                            Files.writeString(readyPath, "installed:$tid")
                        } else {
                            Files.writeString(readyPath, "baseline:$tid")
                        }
                        readyLatch.countDown()

                        // Deliberately trigger the probe syscall. Under guarded mode, survival is a failure.
                        val res =
                            LinuxNative.raw.syscall(
                                victimNr.toLong(),
                                NativeArg.LongArg(0),
                                NativeArg.LongArg(0),
                                NativeArg.LongArg(0),
                                NativeArg.LongArg(0),
                                NativeArg.LongArg(0),
                                NativeArg.LongArg(0),
                            )
                        survived.set(true)
                        Files.writeString(outcomePath, "survived:$tid")
                        if (mode == "guarded") {
                            System.err.println("PROBE_SURVIVED tid=$tid res=$res")
                            Runtime.getRuntime().halt(3)
                        }
                    } catch (t: Throwable) {
                        workerFailure.set(t)
                        readyLatch.countDown()
                    }
                }

        check(readyLatch.await(5, TimeUnit.SECONDS)) { "Probe thread did not reach readiness in mode=$mode" }
        workerFailure.get()?.let { throw IllegalStateException("Probe thread failed in mode=$mode", it) }

        val tid = probeTid.get()
        check(tid > 0) { "Probe thread never recorded kernel tid" }

        if (mode == "guarded") {
            val taskDir = Path.of("/proc/self/task/$tid")
            val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (
                Files.exists(taskDir) &&
                !survived.get() &&
                workerFailure.get() == null &&
                System.nanoTime() < deadlineNanos
            ) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2))
            }
            workerFailure.get()?.let { throw IllegalStateException("Probe thread threw in guarded mode", it) }
            if (survived.get() || Files.exists(taskDir)) {
                if (!Files.exists(outcomePath)) {
                    Files.writeString(outcomePath, "survived:$tid")
                }
                Runtime.getRuntime().halt(3)
            }
            Files.writeString(outcomePath, "killed:$tid")
            // Halt before DestroyJavaVM touches the seccomp-killed HotSpot native thread.
            Runtime.getRuntime().halt(0)
        } else {
            probeThread.join(TimeUnit.SECONDS.toMillis(5))
            workerFailure.get()?.let { throw IllegalStateException("Probe thread failed in mode=$mode", it) }
            check(!probeThread.isAlive) { "Probe thread did not terminate cleanly in mode=$mode" }
        }
    }
}
