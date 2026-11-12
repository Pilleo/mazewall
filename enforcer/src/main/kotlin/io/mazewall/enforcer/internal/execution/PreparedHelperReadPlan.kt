package io.mazewall.enforcer.internal.execution

import io.mazewall.enforcer.api.CanonicalPolicyArtifact
import io.mazewall.enforcer.internal.supervisor.ConfinedManifest
import io.mazewall.enforcer.internal.supervisor.HelperAncestry
import io.mazewall.enforcer.internal.supervisor.HelperCapability
import io.mazewall.enforcer.internal.supervisor.HelperPurpose
import io.mazewall.enforcer.internal.supervisor.SupervisorContext
import io.mazewall.enforcer.internal.supervisor.SupervisorOwnedFd
import io.mazewall.ffi.internal.HelperSecurityObservation
import io.mazewall.ffi.internal.PreparedPath
import io.mazewall.ffi.internal.duplicateHelperResource
import io.mazewall.platform.fd.ResourceRetirement
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

internal data class HelperBinding(
    val ownerPid: Pid,
    val ownerStartTicks: Long,
    val domainGeneration: Long,
    val resourceGeneration: String,
    val policyDigest: String,
)

internal enum class HelperSupportLifetime { Uncontrolled, NoWriters }

/** External reviewed expectations, not digests minted from the same capture being checked. */
internal data class ReviewedHelperSupport(
    val path: Path,
    val sha256: String,
)

/** Private preparation inputs; the runtime snapshots support membership before manager admission. */
internal data class ConfinedHelperConfiguration(
    val policy: CanonicalPolicyArtifact.Worker,
    val context: SupervisorContext,
    val support: List<ReviewedHelperSupport>,
    val lifetime: HelperSupportLifetime,
    val observations: HelperSecurityObservation,
)

/** Owns only separately acquired support pins; the existing resource owner keeps application roots. */
internal class PreparedHelperReadPlan private constructor(
    val manifest: ConfinedManifest,
    private val policy: CanonicalPolicyArtifact.Worker,
    private val resources: PreparedResourceSet,
    private val application: PreparedPath,
    private val support: List<Pair<PreparedPath, String>>,
    private val native: NativeEngine,
) : AutoCloseable {
    val binding: HelperBinding get() = manifest.binding.owner
    private var closed = false

    @Synchronized
    fun validate() {
        validateOwners()
        support.forEach { (pin, digest) ->
            check(PreparedResourceSet.contentDigest(pin) == digest) { "Reviewed helper support content changed: ${pin.requested}" }
            pin.validate()
        }
        (listOf(application) + support.map { it.first }).zip(manifest.capabilities).forEach { (pin, capability) ->
            check(HelperSecurityObservation().observeRoot(pin.descriptor) == capability.identity) { "Helper descriptor differs from captured manifest" }
        }
    }

    private fun validateOwners() {
        check(!closed) { "Helper support owner has retired" }
        resources.requireSource(policy.definition, native)
        resources.validate()
        support.forEach { it.first.validate() }
    }

    @Synchronized
    fun duplicateForTransfer(index: Int): SupervisorOwnedFd {
        require(index in manifest.capabilities.indices)
        // Full-set hashes bracket setup; later roots recheck their own bytes before transfer.
        if (index == 0) validate() else validateOwners()
        val pin = if (index == 0) application else support[index - 1].first
        if (index > 0) {
            check(PreparedResourceSet.contentDigest(pin) == support[index - 1].second) { "Reviewed helper support content changed" }
            pin.validate()
            check(HelperSecurityObservation().observeRoot(pin.descriptor) == manifest.capabilities[index].identity)
        }
        return duplicateHelperResource(pin.descriptor, native)
    }

    @Synchronized
    fun duplicateForTransfer(native: NativeEngine): List<SupervisorOwnedFd> {
        check(native === this.native) { "Helper transfer cannot switch native owner" }
        validate()
        val owned = mutableListOf<SupervisorOwnedFd>()
        return ResourceRetirement.runWithRollback({
            (listOf(application) + support.map { it.first }).forEach { path ->
                owned.add(duplicateHelperResource(path.descriptor, native))
            }
            java.util.List.copyOf(owned)
        }, { retire(owned) })
    }

    @Synchronized
    override fun close() {
        closed = true
        retire(support.map { it.first })
    }

    companion object {
        private const val MAX_SUPPORT_FILES = 64
        private val DIGEST = Regex("[a-f0-9]{64}")

        fun capture(
            policy: CanonicalPolicyArtifact.Worker,
            resources: PreparedResourceSet,
            binding: HelperBinding,
            support: List<ReviewedHelperSupport>,
            lifetime: HelperSupportLifetime,
            native: NativeEngine,
            ancestry: HelperAncestry,
        ): PreparedHelperReadPlan {
            require(lifetime == HelperSupportLifetime.NoWriters) { "Helper runtime support needs a controlled no-writer lifetime" }
            require(binding.ownerPid.value > 0 && binding.ownerStartTicks > 0 && binding.domainGeneration > 0)
            require(binding.resourceGeneration == resources.generation && DIGEST.matches(binding.resourceGeneration)) { "Helper resource generation mismatch" }
            require(
                binding.policyDigest == HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(policy.toJson().toByteArray(Charsets.UTF_8))),
            ) { "Helper canonical policy binding mismatch" }
            requireReadRoutes(policy)
            resources.requireSource(policy.definition, native)
            resources.validate()
            val app = resources.grants.filter { it.purpose == PreparedResourceSet.GrantPurpose.Explicit && it.read && it.path.directory }
            require(app.size == 1) { "Confined helper requires exactly one explicit application read directory" }
            val reviewed = java.util.List.copyOf(support)
            require(
                reviewed.size in 1..MAX_SUPPORT_FILES &&
                    reviewed.map { it.path.toAbsolutePath().normalize() }.distinct().size == reviewed.size,
            ) { "Helper needs 1..64 distinct exact support files" }
            require(reviewed.all { DIGEST.matches(it.sha256) }) { "Helper support requires reviewed SHA-256 expectations" }
            val owned = mutableListOf<PreparedPath>()
            return ResourceRetirement.runWithRollback({
                val pins = reviewed.map { expected ->
                    val pin = PreparedPath.open(expected.path, native).also { owned.add(it) }
                    require(!pin.directory && resources.allowsRead(pin.resolved)) { "Helper runtime support must be an authorized exact regular file" }
                    require(!pin.resolved.startsWith(app.single().path.resolved)) { "Application subtree cannot double as runtime support" }
                    require(
                        resources.grants.none { grant ->
                        grant.write &&
                            (
                                grant.path.resolved == pin.resolved ||
                                grant.path.directory &&
                                pin.resolved.startsWith(grant.path.resolved) ||
                                Files.isSameFile(grant.path.descriptorPath(), pin.descriptorPath())
                            )
                    },
                    ) { "Writable source grant contradicts helper runtime support" }
                    require(owned.dropLast(1).none { Files.isSameFile(it.descriptorPath(), pin.descriptorPath()) }) { "Duplicate helper support object" }
                    require(PreparedResourceSet.contentDigest(pin) == expected.sha256) { "Helper support differs from reviewed bytes" }
                    pin to expected.sha256
                }
                val observation = HelperSecurityObservation()
                val capabilities = listOf(HelperCapability(0, HelperPurpose.Application, observation.observeRoot(app.single().path.descriptor), "0".repeat(64))) +
                    pins.mapIndexed { index, (pin, digest) -> HelperCapability(index + 1, HelperPurpose.RuntimeSupport, observation.observeRoot(pin.descriptor), digest) }
                val manifest = ConfinedManifest.create(binding, ancestry, capabilities)
                PreparedHelperReadPlan(manifest, policy, resources, app.single().path, java.util.List.copyOf(pins), native).also { it.validate() }
            }, { retire(owned) })
        }

        private fun requireReadRoutes(policy: CanonicalPolicyArtifact.Worker) {
            for (syscall in listOf(Syscall.OPEN, Syscall.OPENAT)) {
                val permitted = when (policy.definition.syscallActions[syscall] ?: policy.definition.defaultAction) {
                    SeccompAction.ACT_ALLOW, SeccompAction.ACT_LOG, SeccompAction.ACT_NOTIFY -> true
                    SeccompAction.ACT_KILL_PROCESS, SeccompAction.ACT_KILL_THREAD, SeccompAction.ACT_TRAP,
                    is SeccompAction.ACT_ERRNO, is SeccompAction.ACT_TRACE,
                    -> false
                }
                require(permitted) { "Helper read route $syscall is statically denied" }
            }
        }

        private fun retire(owners: List<AutoCloseable>) {
            val cleanup = ResourceRetirement()
            owners.asReversed().forEach { owner -> cleanup.attempt(owner::close) }
            cleanup.finish()
        }
    }
}
