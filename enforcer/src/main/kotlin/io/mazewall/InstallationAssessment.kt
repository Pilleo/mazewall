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
        val matrix = featureMatrix()
        val tsyncRequired = processWide
        val userNotifRequired = policy.hasSupervisedSyscalls
        val landlockRequired = policy.enforceLandlock
        val blockers = blockers(policy, processWide, virtual, matrix, userNotifRequired, landlockRequired)
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
        matrix: KernelFeatureMatrix,
        userNotifRequired: Boolean,
        landlockRequired: Boolean,
    ): List<InstallationBlocker> =
        listOf(
            InstallationBlocker(InstallationStage.PLATFORM, !Platform.isLinux, "not Linux"),
            InstallationBlocker(InstallationStage.SECCOMP, !Platform.isSupported(), "seccomp is not available or sanity check failed"),
            InstallationBlocker(InstallationStage.VIRTUAL_THREAD, virtualThread, "current thread is virtual; seccomp would poison the carrier"),
            InstallationBlocker(InstallationStage.TSYNC, processWide && !matrix.seccompTsyncSupported, "process-wide install needs SECCOMP_FILTER_FLAG_TSYNC"),
            InstallationBlocker(InstallationStage.USER_NOTIF, userNotifRequired && !matrix.seccompUserNotifSupported, "policy uses USER_NOTIF but the kernel probe failed"),
            InstallationBlocker(InstallationStage.USER_NOTIF, processWide && userNotifRequired, "process-wide USER_NOTIF is unsupported (NEW_LISTENER cannot combine with TSYNC)"),
            InstallationBlocker(InstallationStage.LANDLOCK, landlockRequired && !matrix.landlockSupported, "policy has Landlock paths but landlock ABI is 0"),
            InstallationBlocker(InstallationStage.LANDLOCK, processWide && landlockRequired && !matrix.landlockTsyncSupported, "process-wide Landlock needs TSYNC (Landlock ABI 8+)"),
            InstallationBlocker(InstallationStage.INTEL_CET, policy.lockIntelCet && !matrix.cetSupported, "lockIntelCet is true but Intel CET is not supported on this platform"),
        ).filter(InstallationBlocker::applies)

    private fun warnings(
        fallback: Platform.FallbackBehavior,
        policy: PolicyDefinition<*>,
        processWide: Boolean,
    ): List<String> =
        listOfNotNull(
            "fallback=$fallback does not make an unsupported platform installable".takeIf { fallback != Platform.FallbackBehavior.FAIL },
            "allowMmapExec=false on process-wide policy can fatal a JIT JVM".takeIf { processWide && !policy.allowMmapExec },
        )

    private fun featureMatrix(): KernelFeatureMatrix =
        if (Platform.isLinux) {
            Platform.featureMatrix
        } else {
            KernelFeatureMatrix(
                seccompSupported = false,
                seccompTsyncSupported = false,
                seccompUserNotifSupported = false,
                landlockAbiVersion = 0,
                cetSupported = false,
            )
        }

    private fun policyMode(policy: PolicyDefinition<*>): PolicyMode = if (policy.defaultAction is io.mazewall.core.SeccompAction.ACT_ERRNO) PolicyMode.ALLOW_LIST else PolicyMode.DENY_LIST

    private data class InstallationBlocker(
        val stage: InstallationStage,
        val applies: Boolean,
        val reason: String,
    )
}
