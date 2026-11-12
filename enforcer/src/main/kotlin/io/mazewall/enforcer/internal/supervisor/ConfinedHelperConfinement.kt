package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.DefaultStacktraceScopingPolicy
import io.mazewall.enforcer.api.InstallationEnvironment
import io.mazewall.enforcer.api.Platform
import io.mazewall.enforcer.api.Policy
import io.mazewall.ffi.internal.HelperSecurityObservation
import io.mazewall.ffi.internal.OwnedProcFdReopen
import io.mazewall.ffi.internal.PreparedPath
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.networking.ConfinedDeadline
import io.mazewall.ffi.networking.helperValue
import io.mazewall.landlock.Landlock
import io.mazewall.landlock.LandlockFdOutcome
import io.mazewall.landlock.LandlockRestrictOutcome
import io.mazewall.landlock.LandlockRuleset
import io.mazewall.landlock.RulesetState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.fd.ResourceRetirement
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.RuntimeSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Child operation-thread roots; housekeeping threads never execute delegated application reads. */
internal class ConfinedHelperConfinement(
    private val native: NativeEngine,
    private val observations: HelperSecurityObservation,
    private val channelFd: Int,
) : AutoCloseable {
    private val roots = mutableListOf<SupervisorOwnedFd>()
    private val reopen = OwnedProcFdReopen(native) { Files.readAttributes(it, BasicFileAttributes::class.java) }
    private val proc = PreparedPath.open(Path.of("/proc/self/fd"), native)
    private var sealed = false

    /** Consumes the received descriptor even when its identity or content is rejected. */
    fun adopt(
        capability: HelperCapability,
        owner: SupervisorOwnedFd,
    ) {
        ResourceRetirement.runWithRollback({
            check(!sealed && capability.index == roots.size)
            check(observations.observeRoot(owner.handle) == capability.identity) { "Helper root identity mismatch" }
            if (capability.purpose == HelperPurpose.RuntimeSupport) observations.requireContent(owner.handle, capability.contentDigest)
            roots.add(owner)
        }, owner::close)
    }

    fun seal(
        manifest: ConfinedManifest,
        deadline: ConfinedDeadline,
        recheckAncestry: () -> Unit,
    ) {
        check(!Thread.currentThread().isVirtual) { "Confined helper installation requires a platform thread" }
        check(!sealed && roots.size == manifest.capabilities.size)
        Class.forName(ContainedExecutors::class.java.name)
        val environment = InstallationEnvironment.capture()
        check(native is RuntimeSnapshot && environment.native.engineIdentity === native.engineIdentity && environment.fallback == Platform.FallbackBehavior.FAIL)
        val abi = environment.platform.capabilities.matrix.landlockAbiVersion
        check(abi > 0) { "Confined helper needs Landlock" }
        roots.zip(manifest.capabilities).forEach { (owner, capability) ->
            check(observations.observeRoot(owner.handle) == capability.identity)
            if (capability.purpose == HelperPurpose.RuntimeSupport) observations.requireContent(owner.handle, capability.contentDigest)
        }
        // Resolve the exact reopen/metadata/health path while all proc observations are still permitted.
        health()
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                val ruleset = when (val created = Landlock.tryCreateRuleset(Landlock.getFullAccessMask(abi), 0L, abi, native)) {
                    is LandlockFdOutcome.Ok -> created.fd
                    is LandlockFdOutcome.Err -> throw ConfinedHelperRejection(ConfinedRejectReason.Permission, created.errno)
                }
                SupervisorOwnedFd(ruleset, native.fileSystem).use {
                    val rules = LandlockRuleset<RulesetState.Building>(ruleset, native)
                    addRules(rules, manifest)
                    observations.requireRuntimeInventory(manifest, roots.map { owner -> owner.handle.value }.toSet() + setOf(proc.descriptor.value, channelFd, ruleset.value), native)
                    recheckAncestry()
                    deadline.remainingNanos(ConfinedDeadline.now(native))
                    when (val installed = Landlock.tryEnforceRuleset(rules, processWide = false)) {
                        is LandlockRestrictOutcome.Ok -> Unit
                        is LandlockRestrictOutcome.Err -> throw ConfinedHelperRejection(ConfinedRejectReason.Permission, installed.errno)
                    }
                    ContainedExecutors.installOnCurrentThread(Policy.NO_EXEC.definition.copy(enforceLandlock = false), DefaultStacktraceScopingPolicy, environment).requireInstalled()
                }
            }
        }
        health()
        deadline.remainingNanos(ConfinedDeadline.now(native))
        sealed = true
    }

    context(arena: NativeArena) private fun addRules(
        rules: LandlockRuleset<RulesetState.Building>,
        manifest: ConfinedManifest,
    ) {
        roots.zip(manifest.capabilities).forEach { (owner, capability) ->
            val access = when (capability.purpose) {
                HelperPurpose.Application -> Landlock.LANDLOCK_ACCESS_FS_READ_FILE or Landlock.LANDLOCK_ACCESS_FS_READ_DIR
                HelperPurpose.RuntimeSupport -> Landlock.LANDLOCK_ACCESS_FS_READ_FILE
            }
            when (val added = Landlock.addRuleToRuleset(rules, FileDescriptor.withRole(owner.handle, FileDescriptorRole.OPath), access)) {
                Landlock.AddRuleResult.Success -> Unit
                is Landlock.AddRuleResult.Error -> throw ConfinedHelperRejection(ConfinedRejectReason.Permission, added.errno)
            }
        }
    }

    fun open(request: ConfinedReadRequest): SupervisorDescriptorResult {
        check(sealed)
        val root = roots[request.index]
        return when (request.purpose) {
            HelperPurpose.Application -> when (val pin = reopen.acquire(FileDescriptor.withRole(root.handle, FileDescriptorRole.OPath), request.suffix)) {
                is SupervisorDescriptorResult.Denied -> pin
                is SupervisorDescriptorResult.Opened -> pin.descriptor.produce { reopen.reopenRegular(pin.descriptor, SupervisorOwnedFd(proc.descriptor, native.fileSystem)) }
            }
            HelperPurpose.RuntimeSupport -> reopen.reopenRegular(root, SupervisorOwnedFd(proc.descriptor, native.fileSystem))
        }
    }

    private fun health() {
        when (val result = reopen.reopenRegular(roots[1], SupervisorOwnedFd(proc.descriptor, native.fileSystem))) {
            is SupervisorDescriptorResult.Denied -> throw ConfinedHelperRejection(ConfinedRejectReason.Permission, result.errno)
            is SupervisorDescriptorResult.Opened -> result.descriptor.use { owner ->
                observations.observeReadable(owner.handle, native)
                NativeArena.ofConfined().use { arena ->
                    val buffer = arena.allocate(1)
                    check(native.memory.read(owner.handle, buffer, 1).helperValue("confined helper health read") == 1L) { "Empty helper health resource" }
                }
            }
        }
    }

    override fun close() {
        val cleanup = SocketCleanup()
        roots.asReversed().forEach { cleanup.attempt(it::close) }
        cleanup.attempt(proc::close)
        cleanup.rethrow()
    }
}
