package io.contained

import java.lang.foreign.*
import java.util.concurrent.*
import java.util.logging.Logger

/**
 * Utility for generating a Software Bill of Behaviors (SBoB) by profiling
 * a block of code using seccomp USER_NOTIF.
 * 
 * This implementation uses a "Stability-First" approach for profiling: it 
 * defaults to ALLOW and explicitly traps (NOTIFY) high-value syscalls. 
 * This prevents deadlocks during JVM-internal operations while still 
 * capturing the behaviors needed to build a security policy.
 */
object SBoBGenerator {
    private val logger = Logger.getLogger(SBoBGenerator::class.java.name)

    @Volatile
    private var sharedFd: Int = -1

    /**
     * Profiles the given [task] and returns the unique set of syscall names
     * that were executed during its run.
     */
    fun profile(task: Runnable): Set<String> {
        if (!LibseccompNative.isAvailable) {
            throw UnsupportedOperationException("libseccomp is not available on this system.")
        }

        val syscalls = ConcurrentSkipListSet<String>()
        val archToken = LibseccompNative.archNative()
        val arch = Arch.current()
        sharedFd = -1
        
        // Step 1: Pre-start supervisor in unconstrained context to avoid inheriting the filter
        val supervisorExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r).apply { 
                name = "sbob-supervisor"
                isDaemon = true
            }
        }
        val supervisorTask = supervisorExecutor.submit {
            // Spin-wait for FD (non-blocking, no syscalls while waiting)
            while (sharedFd == -1) {
                if (Thread.currentThread().isInterrupted) return@submit
                Thread.onSpinWait()
            }
            
            val fd = sharedFd
            if (fd < 0) return@submit

            Arena.ofConfined().use { arena ->
                val pair = LibseccompNative.notifyAlloc(arena) ?: return@use
                val req = pair.first
                val resp = pair.second

                // Allocate pollfd once outside the loop
                val pollfd = arena.allocate(8L)
                pollfd.set(ValueLayout.JAVA_INT, 0, fd)
                pollfd.set(ValueLayout.JAVA_SHORT, 4, LinuxNative.POLLIN)

                while (!Thread.currentThread().isInterrupted) {
                    val retPoll = LinuxNative.poll(pollfd, 1, 100)
                    if (retPoll <= 0) continue

                    val retRecv = LibseccompNative.notifyReceive(fd, req)
                    if (retRecv != 0) {
                        if (retRecv == -2) continue // ENOENT (normal during target thread shutdown)
                        println("[SBoB] Supervisor: notifyReceive failed with $retRecv")
                        break
                    }

                    // Extract syscall number
                    val nr = req.get(ValueLayout.JAVA_INT, 16)
                    val name = LibseccompNative.resolveNumArch(archToken, nr) ?: "unknown($nr)"
                    syscalls.add(name)
                    println("[SBoB] Supervisor: Trapped syscall $name ($nr)")

                    // Respond with CONTINUE
                    val id = req.get(ValueLayout.JAVA_LONG, 0)
                    resp.set(ValueLayout.JAVA_LONG, 0, id)
                    resp.set(ValueLayout.JAVA_LONG, 8, 0)
                    resp.set(ValueLayout.JAVA_INT, 16, 0)
                    resp.set(ValueLayout.JAVA_INT, 20, LinuxNative.SECCOMP_USER_NOTIF_FLAG_CONTINUE)

                    val retResp = LibseccompNative.notifyRespond(fd, resp)
                    if (retResp != 0) {
                        println("[SBoB] Supervisor: notifyRespond failed with $retResp for $name")
                    }
                }
                LibseccompNative.notifyFree(req, resp)
            }
        }

        // Step 2: Warm up - ensure all native classes are loaded before traps are active
        LibseccompNative.resolveNumArch(archToken, 0)

        // Step 3: Create target executor
        val targetExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r).apply { name = "sbob-profiler-thread" }
        }

        try {
            val targetTask = targetExecutor.submit {
                println("[SBoB] Target thread starting...")
                val ctx = LibseccompNative.init(LibseccompNative.SCMP_ACT_ALLOW)
                    ?: throw IllegalStateException("Failed to initialize libseccomp")
                
                try {
                    println("[SBoB] Adding NOTIFY rules...")
                    Arena.ofConfined().use { arena ->
                        for (s in Syscall.entries) {
                            val nr = s.numberFor(arch)
                            if (nr >= 0) {
                                LibseccompNative.ruleAdd(ctx, LibseccompNative.SCMP_ACT_NOTIFY, nr)
                            }
                        }

                        val additional = listOf(
                            "open", "read", "write", "unlink", "unlinkat", "rmdir",
                            "mkdir", "mkdirat", "rename", "renameat", "renameat2",
                            "chmod", "fchmod", "fchmodat", "chown", "fchown", "fchownat",
                            "recvfrom", "sendmsg", "recvmsg", "getsockname", "getpeername",
                            "select", "poll", "epoll_wait", "epoll_pwait", "epoll_ctl"
                        )
                        for (name in additional) {
                            val nr = LibseccompNative.resolveName(name, arena)
                            if (nr >= 0) {
                                LibseccompNative.ruleAdd(ctx, LibseccompNative.SCMP_ACT_NOTIFY, nr)
                            }
                        }
                    }

                    println("[SBoB] Loading filter...")
                    val retLoad = LibseccompNative.load(ctx)
                    if (retLoad != 0) throw IllegalStateException("Failed to load seccomp filter: $retLoad")

                    println("[SBoB] Getting notify FD...")
                    val fd = LibseccompNative.notifyFd(ctx)
                    if (fd < 0) throw IllegalStateException("Failed to get notification FD after load")
                    
                    println("[SBoB] Handing off FD: $fd")
                    sharedFd = fd

                    try {
                        println("[SBoB] Running task...")
                        task.run()
                        println("[SBoB] Task completed.")
                    } finally {
                        // Keep supervisor alive
                    }
                } catch (e: Throwable) {
                    println("[SBoB] Target thread error: ${e.message}")
                    e.printStackTrace()
                    sharedFd = -2
                    throw e
                } finally {
                    println("[SBoB] Releasing context...")
                    LibseccompNative.release(ctx)
                }
            }
            targetTask.get(30, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            println("[SBoB] Execution failed: ${e.cause?.message}")
            throw e.cause ?: e
        } finally {
            println("[SBoB] Cleaning up executors...")
            supervisorTask.cancel(true)
            supervisorExecutor.shutdownNow()
            targetExecutor.shutdownNow()
        }

        return syscalls
    }
}
