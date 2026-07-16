package io.mazewall.ffi.networking

import io.mazewall.LinuxNative
import io.mazewall.Platform
import io.mazewall.UnsupportedKernelFeatureException
import io.mazewall.core.Arch
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.NativeArg
import io.mazewall.ffi.NativeConstants
import io.mazewall.getFdOrThrow
import io.mazewall.seccomp.BpfNativeCache
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Unified orchestrator for installing seccomp notification filters and establishing
 * socket handshakes with the supervisor/profiler daemon.
 *
 * This implementation replicates the coordinator pattern from the original profiler
 * to prevent deadlocks when supervising critical JVM operations (like open/openat).
 */
public object SupervisorSeccompNotifInstaller {
    @Suppress("LongParameterList", "ThrowsCount", "MagicNumber", "TooGenericExceptionCaught", "CyclomaticComplexMethod", "LongMethod")
    public fun install(
        socketPath: String,
        filterInstructions: List<io.mazewall.seccomp.BpfInstruction>,
        processWide: Boolean = false,
        connectWithRetry: (String) -> Int = { path -> SupervisorSocketUtils.connectWithRetry(path) },
        sendDescriptor: (Int, Int) -> Boolean = { sockFd, fd -> SupervisorSocketUtils.sendDescriptor(sockFd, fd) },
        onFilterApplied: () -> Unit = {},
        onSocketConnected: (socketFd: Int, readyLatch: CountDownLatch) -> Unit
    ) {
        if (!Platform.featureMatrix.seccompUserNotifSupported) {
            throw UnsupportedKernelFeatureException("Seccomp User Notifications are required.")
        }

        // Mandatory for non-privileged seccomp
        LinuxNative.withTransaction {
            LinuxNative.process.prctl(io.mazewall.core.PrctlCommand.SetNoNewPrivs(true))
        }.getOrThrow("prctl(PR_SET_NO_NEW_PRIVS)")

        val arch = Arch.current()

        // Pre-charge BpfProgram classloading to avoid deadlocks under active seccomp filters
        val dummyBpf = if (processWide) {
            io.mazewall.seccomp.BpfProgram.dsl(arch) { allow() }.instructions
        } else {
            // Also build a dummy program unconditionally to pre-charge classloading
            io.mazewall.seccomp.BpfProgram.dsl(arch) { allow() }.instructions
            null
        }

        // Coordination structures
        val installLatch = CountDownLatch(1)
        val proceedLatch = CountDownLatch(1)
        val listenerFdVal = AtomicInteger(-1)
        val setupError = AtomicReference<Throwable?>()

        // Coordinator thread spawned BEFORE the seccomp filter is active.
        // This ensures the helper is completely uncontained.
        val coordinator = Thread {
            try {
                // Wait for the tracee thread to complete the seccomp installation
                installLatch.await()
                val fd = listenerFdVal.get()
                if (fd < 0) {
                    val err = setupError.get() ?: IllegalStateException("Failed to install seccomp filter")
                    throw err
                }

                // Connect to socket and send the descriptor (runs uncontained)
                val socketFd = connectWithRetry(socketPath)
                try {
                    val sent = sendDescriptor(socketFd, fd)
                    if (!sent) {
                        throw IllegalStateException("Failed to send seccomp listener FD to daemon")
                    }

                    // The daemon now has a copy of the listener FD via SCM_RIGHTS.
                    // We must close our local copy. If we don't, the kernel will not abort
                    // pending notifications when the daemon exits (since our FD would remain open),
                    // causing tracee threads to deadlock forever in __seccomp_filter.
                    LinuxNative.fileSystem.close(FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(fd))

                    val readyLatch = CountDownLatch(1)

                    // Start validation/event listener thread (which will run uncontained)
                    onSocketConnected(socketFd, readyLatch)

                    // Wait until the listener is fully initialized and ready
                    readyLatch.await()
                } catch (t: Throwable) {
                    LinuxNative.fileSystem.close(FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(socketFd))
                    throw t
                }

                // Handshake is fully configured, wake up tracee thread
                proceedLatch.countDown()
            } catch (t: Throwable) {
                setupError.set(t)
                proceedLatch.countDown()
            }
        }.apply {
            isDaemon = true
            name = "seccomp-install-coordinator"
            start()
        }

        try {
            val prog = BpfNativeCache.getOrCompute(filterInstructions)

            // Install seccomp user notifier filter (applied to the current tracee thread)
            val r = LinuxNative.withTransaction {
                LinuxNative.raw.syscall(
                    arch.seccompSyscallNumber.toLong(),
                    NativeArg.LongArg(NativeConstants.SECCOMP_SET_MODE_FILTER.toLong()),
                    NativeArg.LongArg(NativeConstants.SECCOMP_FILTER_FLAG_NEW_LISTENER.toLong()),
                    NativeArg.MemoryArg(prog),
                )
            }

            val rawFd = when (r) {
                is LinuxNative.SyscallResult.Success -> r.value.toInt()
                is LinuxNative.SyscallResult.Error -> {
                    setupError.set(IllegalStateException("seccomp syscall failed with errno ${r.errno}"))
                    installLatch.countDown()
                    proceedLatch.countDown()
                    return
                }
            }

            listenerFdVal.set(rawFd)
            onFilterApplied()
            installLatch.countDown() // Release the coordinator to connect & send descriptor

            // Block tracee thread until the coordinator completes descriptor passing and listener startup
            // We wait uninterruptibly to ensure the handshake completes even if the thread is interrupted
            // during executor shutdown. This prevents state desync between the kernel and JVM.
            var interrupted = false
            while (true) {
                try {
                    proceedLatch.await()
                    break
                } catch (e: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt()
            }

            setupError.get()?.let { throw it }

            // Step 2: Synchronize filter tree process-wide if processWide is true
            // We run this AFTER the coordinator has connected, handshaked, and started the listener.
            // This prevents the coordinator and listener threads from being subject to the seccomp
            // filter during their startup and handshake, avoiding fatal circular deadlocks.
            if (processWide && dummyBpf != null) {
                val dummyProg = BpfNativeCache.getOrCompute(dummyBpf)
                val tsyncRes = LinuxNative.withTransaction {
                    LinuxNative.raw.syscall(
                        arch.seccompSyscallNumber.toLong(),
                        NativeArg.LongArg(NativeConstants.SECCOMP_SET_MODE_FILTER.toLong()),
                        NativeArg.LongArg(NativeConstants.SECCOMP_FILTER_FLAG_TSYNC.toLong()),
                        NativeArg.MemoryArg(dummyProg),
                    )
                }
                if (tsyncRes is LinuxNative.SyscallResult.Error) {
                    val errno = tsyncRes.errno
                    if (errno == 13) {
                        throw IllegalStateException(
                            "Process-wide profiling failed with EACCES (Permission denied). " +
                            "This typically occurs because sibling threads (such as GC or JIT compiler threads) " +
                            "do not have the 'no_new_privs' flag set. Process-wide profiling requires running " +
                            "inside a container (where privilege escalation is disabled at the container boundary)."
                        )
                    } else {
                        throw IllegalStateException("Process-wide profiling TSYNC failed with errno $errno")
                    }
                }
            }
        } catch (t: Throwable) {
            val fd = listenerFdVal.get()
            if (fd >= 0) {
                LinuxNative.fileSystem.close(FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(fd))
            }
            throw t
        }
    }
}
