package io.contained

import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.lang.foreign.ValueLayout

/**
 * Seccomp engine implementation using the native libseccomp library.
 */
object LibseccompEngine : SeccompEngine {

    override val isSupported: Boolean
        get() = LibseccompNative.isAvailable

    override fun install(policy: Policy) {
        val arch = Arch.current()
        
        // Step 1: Set no_new_privs
        val r1 = LinuxNative.prctl(LinuxNative.PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0)
        if (r1.returnValue != 0) {
            throw IllegalStateException("prctl(PR_SET_NO_NEW_PRIVS) failed: ${LinuxNative.strerror(r1.errno)}")
        }

        // Step 2: Initialize Context
        val ctx = LibseccompNative.init(LibseccompNative.SCMP_ACT_ALLOW)
            ?: throw IllegalStateException("seccomp_init failed")

        try {
            // Step 3: Add Mandatory Security Rules
            addMmapRule(ctx)
            addCloneRules(ctx, arch)

            // Step 4: Add Rules for Blocked Syscalls
            for (syscall in policy.blocked) {
                val nr = syscall.numberFor(arch)
                if (nr == -1) continue

                // Skip handled cases
                if (syscall == Syscall.MMAP || syscall == Syscall.CLONE || syscall == Syscall.CLONE3) {
                    continue
                }

                // General block: EPERM
                val ret = LibseccompNative.ruleAdd(ctx, LibseccompNative.SCMP_ACT_ERRNO(LinuxNative.EPERM), nr)
                if (ret != 0) {
                    throw IllegalStateException("seccomp_rule_add failed for syscall $syscall (ret=$ret)")
                }
            }

            // Step 5: Load Filter
            val retLoad = LibseccompNative.load(ctx)
            if (retLoad != 0) {
                throw IllegalStateException("seccomp_load failed (ret=$retLoad)")
            }
        } finally {
            LibseccompNative.release(ctx)
        }
    }

    private fun addMmapRule(ctx: java.lang.foreign.MemorySegment) {
        Arena.ofConfined().use { arena ->
            // Block if (arg[2] & PROT_EXEC) != 0
            val cmp = arena.allocate(LibseccompNative.SCMP_ARG_CMP_LAYOUT)
            cmp.set(ValueLayout.JAVA_INT, 0, 2) // arg 2 (prot)
            cmp.set(ValueLayout.JAVA_INT, 4, LibseccompNative.SCMP_CMP_MASKED_EQ) // op
            cmp.set(ValueLayout.JAVA_LONG, 8, 0x04L) // datum_a (PROT_EXEC)
            cmp.set(ValueLayout.JAVA_LONG, 16, 0x04L) // datum_b (value to match)

            val nr = Arch.current().mmap
            val ret = LibseccompNative.ruleAddArray(ctx, LibseccompNative.SCMP_ACT_ERRNO(LinuxNative.EPERM), nr, 1, cmp)
            if (ret != 0) throw IllegalStateException("seccomp_rule_add_array failed for mmap")
        }
    }

    private fun addCloneRules(ctx: java.lang.foreign.MemorySegment, arch: Arch) {
        Arena.ofConfined().use { arena ->
            // Block if (flags & (CLONE_THREAD | CLONE_VM)) == 0
            val cmp = arena.allocate(LibseccompNative.SCMP_ARG_CMP_LAYOUT)
            cmp.set(ValueLayout.JAVA_INT, 0, 0) // arg 0 (flags)
            cmp.set(ValueLayout.JAVA_INT, 4, LibseccompNative.SCMP_CMP_MASKED_EQ)
            cmp.set(ValueLayout.JAVA_LONG, 8, 0x00010100L) // Mask
            cmp.set(ValueLayout.JAVA_LONG, 16, 0L) // Value (if masked result is 0, then trap)

            val ret1 = LibseccompNative.ruleAddArray(ctx, LibseccompNative.SCMP_ACT_ERRNO(LinuxNative.EPERM), arch.clone, 1, cmp)
            if (ret1 != 0) throw IllegalStateException("seccomp_rule_add_array failed for clone")
            
            // clone3: Force ENOSYS fallback
            val ret2 = LibseccompNative.ruleAdd(ctx, LibseccompNative.SCMP_ACT_ERRNO(38), arch.clone3)
            if (ret2 != 0) throw IllegalStateException("seccomp_rule_add failed for clone3")
        }
    }
}
