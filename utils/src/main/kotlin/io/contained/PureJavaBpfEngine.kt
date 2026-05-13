package io.contained

import java.lang.foreign.Arena

/**
 * Pure Java implementation of the seccomp engine.
 * Generates BPF filters manually and installs them using Downcalls.
 */
object PureJavaBpfEngine : SeccompEngine {
    
    override val isSupported: Boolean
        get() = Platform.isSupported()

    override fun install(policy: Policy) {
        // Step 1: Set no_new_privs (mandatory for non-root seccomp)
        val r1 = LinuxNative.prctl(LinuxNative.PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0)
        if (r1.returnValue != 0) {
            throw IllegalStateException("prctl(PR_SET_NO_NEW_PRIVS) failed: ${LinuxNative.strerror(r1.errno)}")
        }

        val arch = Arch.current()
        val filters = BpfFilter.build(arch, policy)
        
        Arena.ofConfined().use { arena ->
            val prog = LinuxNative.newSockFProg(arena, filters)

            // Try modern seccomp(2) syscall first
            val r3 = LinuxNative.syscall(
                arch.seccompSyscallNumber.toLong(),
                LinuxNative.SECCOMP_SET_MODE_FILTER.toLong(),
                0L,
                prog
            )

            if (r3.returnValue != 0) {
                // Fall back to prctl for older kernels
                val errno1 = r3.errno
                val r4 = LinuxNative.prctl(
                    LinuxNative.PR_SET_SECCOMP,
                    LinuxNative.SECCOMP_MODE_FILTER.toLong(),
                    prog,
                    0, 0
                )
                
                if (r4.returnValue != 0) {
                    throw IllegalStateException(
                        "seccomp installation failed: seccomp(2) errno=${LinuxNative.strerror(errno1)}, prctl errno=${LinuxNative.strerror(r4.errno)}"
                    )
                }
            }
        }
        
        // Verify filter is actually installed
        val r5 = LinuxNative.prctl(LinuxNative.PR_GET_SECCOMP, 0, 0, 0, 0)
        if (r5.returnValue != 2) {
            throw IllegalStateException(
                "Seccomp filter verification failed: expected mode 2, got ${r5.returnValue}"
            )
        }
    }
}
