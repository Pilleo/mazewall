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
        val matrix = Platform.featureMatrix
        val virtual = Thread.currentThread().isVirtual
        val fallback = Platform.configuredFallback()
        val warnings = mutableListOf<String>()
        val reasons = mutableListOf<String>()
        val stages = mutableListOf<InstallationStage>()

        if (fallback != Platform.FallbackBehavior.FAIL) {
            warnings.add("fallback=$fallback does not make an unsupported platform installable")
        }

        if (!Platform.isLinux) {
            reasons.add("not Linux")
            stages.add(InstallationStage.PLATFORM)
        }
        if (!Platform.isSupported()) {
            reasons.add("seccomp is not available or sanity check failed")
            stages.add(InstallationStage.SECCOMP)
        }
        if (virtual) {
            reasons.add("current thread is virtual; seccomp would poison the carrier")
            stages.add(InstallationStage.VIRTUAL_THREAD)
        }

        val tsyncRequired = processWide
        if (tsyncRequired && !matrix.seccompTsyncSupported) {
            reasons.add("process-wide install needs SECCOMP_FILTER_FLAG_TSYNC")
            stages.add(InstallationStage.TSYNC)
        }

        val userNotifRequired = policy.hasSupervisedSyscalls
        if (userNotifRequired && !matrix.seccompUserNotifSupported) {
            reasons.add("policy uses USER_NOTIF but the kernel probe failed")
            stages.add(InstallationStage.USER_NOTIF)
        }
        if (processWide && userNotifRequired) {
            reasons.add("process-wide USER_NOTIF is unsupported (NEW_LISTENER cannot combine with TSYNC)")
            stages.add(InstallationStage.USER_NOTIF)
        }

        val landlockRequired = policy.enforceLandlock
        if (landlockRequired && !matrix.landlockSupported) {
            reasons.add("policy has Landlock paths but landlock ABI is 0")
            stages.add(InstallationStage.LANDLOCK)
        }
        if (processWide && landlockRequired && !matrix.landlockTsyncSupported) {
            reasons.add("process-wide Landlock needs TSYNC (Landlock ABI 8+)")
            stages.add(InstallationStage.LANDLOCK)
        }

        if (policy.lockIntelCet && !matrix.cetSupported) {
            reasons.add("lockIntelCet is true but Intel CET is not supported on this platform")
            stages.add(InstallationStage.INTEL_CET)
        }

        if (processWide && !policy.allowMmapExec) {
            warnings.add("allowMmapExec=false on process-wide policy can fatal a JIT JVM")
        }

        return InstallationAssessment(
            scope = scope,
            installable = reasons.isEmpty(),
            fallback = fallback,
            argumentRules = policy.argumentRules,
            mode =
                if (policy.defaultAction is io.mazewall.core.SeccompAction.ACT_ERRNO ||
                    policy.defaultAction == io.mazewall.core.SeccompAction.ACT_ERRNO
                ) {
                    PolicyMode.ALLOW_LIST
                } else {
                    PolicyMode.DENY_LIST
                },
            warnings = warnings,
            blockingReasons = reasons,
            blockedStages = stages.distinct(),
            landlockRequired = landlockRequired,
            landlockAbi = matrix.landlockAbiVersion,
            userNotifRequired = userNotifRequired,
            tsyncRequired = tsyncRequired,
            virtualThread = virtual,
            seccompSupported = Platform.isSupported(),
        )
    }
}
