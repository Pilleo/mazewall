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
import io.mazewall.ffi.memory.nativeScope
import io.mazewall.getFdOrThrow
import java.lang.foreign.MemorySegment
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

/**
 * Unified orchestrator for installing seccomp notification filters and establishing
 * socket handshakes with the supervisor/profiler daemon.
 */
public object SupervisorSeccompNotifInstaller {
    @Suppress("LongParameterList", "ThrowsCount", "MagicNumber", "TooGenericExceptionCaught")
    public fun install(
        socketPath: String,
        filterInstructions: List<io.mazewall.seccomp.BpfInstruction>,
        processWide: Boolean = false,
        connectWithRetry: (String) -> Int = { path -> SupervisorSocketUtils.connectWithRetry(path) },
        sendDescriptor: (Int, Int) -> Boolean = { sockFd, fd -> SupervisorSocketUtils.sendDescriptor(sockFd, fd) },
        onSocketConnected: (socketFd: Int) -> Unit
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

        val socketFd = connectWithRetry(socketPath)

        // ARCHITECTURAL INVARIANT: Spawning validation/listener threads and performing all related
        // classloading MUST occur BEFORE the seccomp filter is installed on the current thread.
        // If we sandbox the thread first, any subsequent JVM classloader operations (which trigger 'openat')
        // will get trapped by seccomp, blocking the installer thread and causing a circular deadlock
        // since the daemon blocks waiting for an ACK from the listener thread that hasn't started yet.
        onSocketConnected(socketFd)

        // Helper thread to send seccomp listener FD to daemon
        val listenerFdPromise = CompletableFuture<Int>()
        val setupError = AtomicReference<Throwable?>()
        val setupHelper = Thread {
            try {
                val listenerFdValue = listenerFdPromise.get()
                val sent = sendDescriptor(socketFd, listenerFdValue)
                if (!sent) {
                    setupError.set(IllegalStateException("Failed to send seccomp listener FD to daemon"))
                }
            } catch (e: InterruptedException) {
                setupError.set(e)
            } catch (e: java.util.concurrent.ExecutionException) {
                setupError.set(e)
            }
        }.apply {
            isDaemon = true
            name = "seccomp-setup-helper"
            start()
        }

        try {
            nativeScope {
                val prog = LinuxNative.memory.newSockFProg(filterInstructions)
                val r = LinuxNative.withTransaction {
                    LinuxNative.syscall(
                        arch.seccompSyscallNumber.toLong(),
                        NativeArg.LongArg(NativeConstants.SECCOMP_SET_MODE_FILTER.toLong()),
                        NativeArg.LongArg(NativeConstants.SECCOMP_FILTER_FLAG_NEW_LISTENER.toLong()),
                        NativeArg.MemoryArg(prog),
                    )
                }
                val listenerFd = r.getFdOrThrow("seccomp(SECCOMP_FILTER_FLAG_NEW_LISTENER)")
                    .let { FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(it.value) }

                // Step 2: Synchronize filter tree process-wide if processWide is true
                if (processWide && dummyBpf != null) {
                    val dummyProg = LinuxNative.memory.newSockFProg(dummyBpf)
                    val tsyncRes = LinuxNative.withTransaction {
                        LinuxNative.syscall(
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

                listenerFdPromise.complete(listenerFd.value)

                setupHelper.join()
                setupError.get()?.let { throw it }
            }
        } catch (t: Throwable) {
            // Clean up socketFd if handshake fails
            LinuxNative.fileSystem.close(FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(socketFd))
            throw t
        }
    }
}
