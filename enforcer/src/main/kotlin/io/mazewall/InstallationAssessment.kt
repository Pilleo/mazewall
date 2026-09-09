package io.mazewall

/**
 * Where a policy would be installed. Assessment does not mutate kernel state.
 */
public enum class InstallationScope {
    PROCESS,
    THREAD,
}

/** Stage that made [InstallationAssessment.installable] false. */
public enum class InstallationStage {
    PLATFORM,
    VIRTUAL_THREAD,
    SECCOMP,
    TSYNC,
    USER_NOTIF,
    LANDLOCK,
    INTEL_CET,
}

public class InstallationRejectedException(
    public val stages: List<InstallationStage>,
    public val reasons: List<String>,
) : IllegalStateException(
        "Policy is not installable (${stages.joinToString()}): ${reasons.joinToString("; ")}",
    )

/**
 * Read-only preflight. [installable] is never true because fallback is WARN/SILENT;
 * those modes are reported on [fallback] only.
 */
public data class InstallationAssessment(
    public val scope: InstallationScope,
    public val installable: Boolean,
    public val fallback: Platform.FallbackBehavior,
    public val argumentRules: PolicyArgumentRules,
    public val mode: PolicyMode,
    public val warnings: List<String>,
    public val blockingReasons: List<String>,
    public val blockedStages: List<InstallationStage>,
    public val landlockRequired: Boolean,
    public val landlockAbi: Int,
    public val userNotifRequired: Boolean,
    public val tsyncRequired: Boolean,
    public val virtualThread: Boolean,
    public val seccompSupported: Boolean,
) {
    public fun requireInstallable(): InstallationAssessment {
        if (!installable) {
            throw InstallationRejectedException(blockedStages, blockingReasons)
        }
        return this
    }
}

public object InstallationAssessor {
    public fun assess(
        policy: PolicyDefinition<*>,
        processWide: Boolean,
    ): InstallationAssessment {
        val scope = if (processWide) InstallationScope.PROCESS else InstallationScope.THREAD
        val virtual = Thread.currentThread().isVirtual
        val fallback = Platform.configuredFallback()
        val snapshot = featureSnapshot()
        val matrix = snapshot.matrix
        val tsyncRequired = processWide
        val userNotifRequired = policy.hasSupervisedSyscalls
        val landlockRequired = policy.enforceLandlock
        val blockers = blockers(policy, processWide, virtual, snapshot, userNotifRequired, landlockRequired)
        val warnings = warnings(fallback, policy, processWide)

        return InstallationAssessment(
            scope = scope,
            installable = blockers.isEmpty(),
            fallback = fallback,
            argumentRules = policy.argumentRules,
            mode = policyMode(policy),
            warnings = warnings,
            blockingReasons = blockers.map { it.reason },
            blockedStages = blockers.map { it.stage }.distinct(),
            landlockRequired = landlockRequired,
            landlockAbi = matrix.landlockAbiVersion,
            userNotifRequired = userNotifRequired,
            tsyncRequired = tsyncRequired,
            virtualThread = virtual,
            seccompSupported = Platform.isSupported(),
        )
    }

    private fun blockers(
        policy: PolicyDefinition<*>,
        processWide: Boolean,
        virtualThread: Boolean,
        snapshot: KernelFeatureSnapshot,
        userNotifRequired: Boolean,
        landlockRequired: Boolean,
    ): List<InstallationBlocker> {
        val matrix = snapshot.matrix
        return listOfNotNull(
            InstallationBlocker(InstallationStage.PLATFORM, !Platform.isLinux, "not Linux"),
            InstallationBlocker(InstallationStage.SECCOMP, !Platform.isSupported(), "seccomp is not available or sanity check failed"),
            InstallationBlocker(InstallationStage.VIRTUAL_THREAD, virtualThread, "current thread is virtual; seccomp would poison the carrier"),
            InstallationBlocker(InstallationStage.TSYNC, processWide && !matrix.seccompTsyncSupported, "process-wide install needs SECCOMP_FILTER_FLAG_TSYNC"),
            InstallationBlocker(InstallationStage.USER_NOTIF, userNotifRequired && !matrix.seccompUserNotifSupported, "policy uses USER_NOTIF but the kernel probe failed"),
            InstallationBlocker(InstallationStage.USER_NOTIF, processWide && userNotifRequired, "process-wide USER_NOTIF is unsupported (NEW_LISTENER cannot combine with TSYNC)"),
            landlockCapabilityBlocker(landlockRequired, snapshot.landlockCapability),
            InstallationBlocker(InstallationStage.LANDLOCK, processWide && landlockRequired && !matrix.landlockTsyncSupported, "process-wide Landlock needs TSYNC (Landlock ABI 8+)"),
            InstallationBlocker(InstallationStage.INTEL_CET, policy.lockIntelCet && !matrix.cetSupported, "lockIntelCet is true but Intel CET is not supported on this platform"),
        ).filter(InstallationBlocker::applies)
    }

    private fun landlockCapabilityBlocker(
        required: Boolean,
        capability: KernelCapability<Int>,
    ): InstallationBlocker? {
        if (!required) return null
        val reason =
            when (capability) {
                is KernelCapability.Available -> return null
                KernelCapability.Unsupported -> "landlock capability is unsupported"
                is KernelCapability.Rejected -> "landlock capability rejected with errno ${capability.errno}"
                is KernelCapability.ProbeFailed ->
                    "landlock capability probe failed: ${capability.cause.message ?: capability.cause::class.java.name}"
            }
        return InstallationBlocker(InstallationStage.LANDLOCK, true, reason)
    }

    private fun warnings(
        fallback: Platform.FallbackBehavior,
        policy: PolicyDefinition<*>,
        processWide: Boolean,
    ): List<String> =
        listOfNotNull(
            "fallback=$fallback does not make an unsupported platform installable".takeIf { fallback != Platform.FallbackBehavior.FAIL },
            "allowMmapExec=false on process-wide policy can fatal a JIT JVM".takeIf { processWide && !policy.allowMmapExec },
        )

    private fun featureSnapshot(): KernelFeatureSnapshot =
        if (Platform.isLinux) {
            Platform.featureSnapshot
        } else {
            KernelFeatureSnapshot(
                matrix =
                    KernelFeatureMatrix(
                        seccompSupported = false,
                        seccompTsyncSupported = false,
                        seccompUserNotifSupported = false,
                        landlockAbiVersion = 0,
                        cetSupported = false,
                    ),
                landlockCapability = KernelCapability.Unsupported,
            )
        }

    private fun policyMode(policy: PolicyDefinition<*>): PolicyMode = if (policy.defaultAction is io.mazewall.core.SeccompAction.ACT_ERRNO) PolicyMode.ALLOW_LIST else PolicyMode.DENY_LIST

    private data class InstallationBlocker(
        val stage: InstallationStage,
        val applies: Boolean,
        val reason: String,
    )
}
