package io.mazewall.profiler.internal.attribution

/** Result of admitting a definition into a live snapshot/replay ledger. */
internal enum class DefinitionAdmission {
    NEW,
    DUPLICATE_IDENTICAL,
    CONFLICTING,
}

/**
 * Session-local identity/content ledger for definitions received through live
 * publication.  Identity collisions are never silently replaced.
 */
internal class SnapshotReplayAdmission {
    private val stacks = mutableMapOf<StackTraceId, StackDefinition>()
    private val invocations = mutableMapOf<InvocationId, InvocationDefinition>()

    fun admit(definition: StackDefinition): DefinitionAdmission {
        val previous = stacks[definition.stackTraceId]
        return if (previous == null) {
            stacks[definition.stackTraceId] = definition
            DefinitionAdmission.NEW
        } else if (previous == definition) {
            DefinitionAdmission.DUPLICATE_IDENTICAL
        } else {
            DefinitionAdmission.CONFLICTING
        }
    }

    fun admit(definition: InvocationDefinition): DefinitionAdmission {
        val previous = invocations[definition.invocationId]
        return if (previous == null) {
            invocations[definition.invocationId] = definition
            DefinitionAdmission.NEW
        } else if (previous == definition) {
            DefinitionAdmission.DUPLICATE_IDENTICAL
        } else {
            DefinitionAdmission.CONFLICTING
        }
    }
}
