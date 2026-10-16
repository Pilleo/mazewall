package io.mazewall.sbob

import io.mazewall.enforcer.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.BillOfBehaviorDto
import io.mazewall.enforcer.api.PolicyBuilder
import io.mazewall.enforcer.api.PolicyDefinition
import io.mazewall.enforcer.api.PolicyScope
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.state.*
import io.mazewall.platform.lifecycle.SandboxedPath
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall

/**
 * Transforms a DTO and normalized paths into a Mazewall [PolicyDefinition].
 */
internal object PolicyTransformer {
    /**
     * Transforms the [dto] and [prunedReads]/[prunedWrites] into a [PolicyDefinition] based on the [base] policy.
     */
    fun transform(
        dto: BillOfBehaviorDto,
        prunedReads: Set<String>,
        prunedWrites: Set<String>,
        base: PolicyDefinition<*>,
    ): PolicyDefinition<PolicyScope.ThreadLocalOnly> {
        val mappedSyscalls = dto.syscalls
            .mapNotNull { name ->
                try {
                    Syscall.valueOf(name.uppercase())
                } catch (ignored: IllegalArgumentException) {
                    null
                }
            }.toSet()

        // SBoB parsing may result in Landlock rules, so we transition to ThreadLocalOnly

        var builder = PolicyBuilder<PolicyScope.ThreadLocalOnly>().base(base as PolicyDefinition<PolicyScope.ThreadLocalOnly>)

        builder = if (base.defaultAction == SeccompAction.ACT_ALLOW) {
            val toUnblock = mappedSyscalls.filter { base.syscallActions.containsKey(it) }
            builder.unblock(*toUnblock.toTypedArray())
        } else {
            builder.allow(*mappedSyscalls.toTypedArray())
        }

        // allowFs* are copy-on-promotion: the returned thread-local builder must be kept.
        for (path in prunedReads) builder = builder.allowFsRead(SandboxedPath.of(path, allowNonExistent = true))
        for (path in prunedWrites) builder = builder.allowFsWrite(SandboxedPath.of(path, allowNonExistent = true))

        return builder.build()
    }
}
