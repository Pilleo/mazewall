package io.mazewall.enforcer.api

import io.mazewall.enforcer.internal.artifact.CanonicalPolicyJson

/** Versioned policy bytes preserve intent; they do not confer review or approval authority. */
public sealed class CanonicalPolicyArtifact(
    public val runtime: RuntimeProfile,
) {
    public abstract val definition: PolicyDefinition<*>

    public fun toJson(): String = CanonicalPolicyJson.encode(this)

    public class Worker internal constructor(
        runtime: RuntimeProfile,
        definition: PolicyDefinition<PolicyScope.ThreadLocalOnly>,
    ) : CanonicalPolicyArtifact(runtime) {
        override val definition: PolicyDefinition<PolicyScope.ThreadLocalOnly> = definition.frozenCopy()
        public val policy: Policy<PolicyScope.ThreadLocalOnly, Uncompiled> get() = Policy(definition)
    }

    public class Process internal constructor(
        runtime: RuntimeProfile,
        definition: PolicyDefinition<PolicyScope.ProcessWideSafe>,
    ) : CanonicalPolicyArtifact(runtime) {
        override val definition: PolicyDefinition<PolicyScope.ProcessWideSafe> = definition.frozenCopy()
        public val policy: Policy<PolicyScope.ProcessWideSafe, Uncompiled> get() = Policy(definition)

        init {
            require(!definition.enforceLandlock && definition.allowedFsReadPaths.isEmpty() && definition.allowedFsWritePaths.isEmpty()) { "Process artifacts cannot contain worker filesystem intent" }
        }
    }

    public companion object {
        @JvmStatic
        public fun worker(
            runtime: RuntimeProfile,
            policy: Policy<PolicyScope.ThreadLocalOnly, Uncompiled>,
        ): Worker = Worker(runtime, policy.definition)

        @JvmStatic
        public fun process(
            runtime: RuntimeProfile,
            policy: Policy<PolicyScope.ProcessWideSafe, Uncompiled>,
        ): Process = Process(runtime, policy.definition)

        @JvmStatic
        public fun fromJson(document: String): CanonicalPolicyArtifact = CanonicalPolicyJson.decode(document)
    }
}
