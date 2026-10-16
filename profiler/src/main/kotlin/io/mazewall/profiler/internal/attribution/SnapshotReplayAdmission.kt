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

    fun inspect(definition: StackDefinition): DefinitionAdmission =
        when (stacks[definition.stackTraceId]) {
            null -> DefinitionAdmission.NEW
            definition -> DefinitionAdmission.DUPLICATE_IDENTICAL
            else -> DefinitionAdmission.CONFLICTING
        }

    fun inspect(definition: InvocationDefinition): DefinitionAdmission =
        when (invocations[definition.invocationId]) {
            null -> DefinitionAdmission.NEW
            definition -> DefinitionAdmission.DUPLICATE_IDENTICAL
            else -> DefinitionAdmission.CONFLICTING
        }

    fun admit(definition: StackDefinition): DefinitionAdmission {
        val result = inspect(definition)
        if (result == DefinitionAdmission.NEW) stacks[definition.stackTraceId] = definition
        return result
    }

    fun admit(definition: InvocationDefinition): DefinitionAdmission {
        val result = inspect(definition)
        if (result == DefinitionAdmission.NEW) invocations[definition.invocationId] = definition
        return result
    }
}
