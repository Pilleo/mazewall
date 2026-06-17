package io.mazewall.sbob

import io.mazewall.BillOfBehaviorDto
import io.mazewall.Policy
import io.mazewall.PolicyScope
import io.mazewall.Uncompiled
import io.mazewall.core.SandboxedPath
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall

/**
 * Transforms a DTO and normalized paths into a Mazewall [Policy].
 */
internal object PolicyTransformer {
    /**
     * Transforms the [dto] and [prunedReads]/[prunedWrites] into a [Policy] based on the [base] policy.
     */
    fun transform(
        dto: BillOfBehaviorDto,
        prunedReads: Set<String>,
        prunedWrites: Set<String>,
        base: Policy<*, Uncompiled>
    ): Policy<PolicyScope.ThreadLocalOnly, Uncompiled> {
        val mappedSyscalls = dto.syscalls
            .mapNotNull { name ->
                try {
                    Syscall.valueOf(name.uppercase())
                } catch (ignored: IllegalArgumentException) {
                    null
                }
            }.toSet()

        // SBoB parsing may result in Landlock rules, so we transition to ThreadLocalOnly
        @Suppress("UNCHECKED_CAST")
        val builder = Policy.threadLocalBuilder().base(base as Policy<PolicyScope.ThreadLocalOnly, *>)

        if (base.defaultAction == SeccompAction.ACT_ALLOW) {
            val toUnblock = mappedSyscalls.filter { base.syscallActions.containsKey(it) }
            builder.unblock(*toUnblock.toTypedArray())
        } else {
            builder.allow(*mappedSyscalls.toTypedArray())
        }

        for (path in prunedReads) builder.allowFsRead(SandboxedPath.of(path, allowNonExistent = true))
        for (path in prunedWrites) builder.allowFsWrite(SandboxedPath.of(path, allowNonExistent = true))

        return builder.build()
    }
}
