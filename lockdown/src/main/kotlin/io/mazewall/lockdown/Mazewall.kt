package io.mazewall.lockdown

import io.mazewall.lockdown.internal.enforcer.api.PolicyDefinition
import io.mazewall.lockdown.internal.enforcer.api.PolicyScope
import io.mazewall.lockdown.internal.seccomp.ProcessFilterInstaller
import java.util.Objects

/**
 * Entry point for installing irreversible process-wide seccomp lockdown policies.
 */
public object Mazewall {
    /**
     * Installs one or more process-wide policies onto the current Linux process using
     * `SECCOMP_FILTER_FLAG_TSYNC`, failing closed with [MazewallInstallationException]
     * whenever the host, kernel, native access, thread type, profile compatibility, or
     * verification state cannot enforce lockdown.
     */
    @JvmStatic
    public fun installOnProcess(
        first: ProcessPolicy,
        vararg more: ProcessPolicy,
    ): ProcessInstallation {
        val combined = combinePolicies(first, more)
        val installed = installTranslatingNativeErrors(combined)
        return ProcessInstallation.create(
            filterDepth = installed.filterDepth,
            architecture = installed.arch.name.lowercase(),
        )
    }

    private fun combinePolicies(
        first: ProcessPolicy,
        more: Array<out ProcessPolicy>,
    ): PolicyDefinition<PolicyScope.ProcessWideSafe> {
        val policyObjects =
            buildList(1 + more.size) {
                add(Objects.requireNonNull(first, "ProcessPolicy first argument must not be null"))
                more.forEachIndexed { index, policy ->
                    add(Objects.requireNonNull(policy, "ProcessPolicy vararg argument at index $index must not be null"))
                }
            }
        val distinctProfiles = policyObjects.map { it.runtime }.distinct()
        if (distinctProfiles.size > 1) {
            throw MazewallInstallationException(
                "Conflicting RuntimeProfile values in installOnProcess call: $distinctProfiles",
            )
        }
        return if (policyObjects.size == 1) {
            policyObjects.single().definition
        } else {
            PolicyDefinition.combineAll(policyObjects.map { it.definition })
        }
    }

    private fun installTranslatingNativeErrors(combined: PolicyDefinition<PolicyScope.ProcessWideSafe>): ProcessFilterInstaller.InstalledProcessFilter =
        try {
            installTranslatingExceptions(combined)
        } catch (initError: ExceptionInInitializerError) {
            throw wrapNativeInitFailure(initError)
        } catch (linkageError: NoClassDefFoundError) {
            throw wrapOrPreserveLinkageError(linkageError)
        }

    @Suppress("TooGenericExceptionCaught") // Wraps all non-fatal install failures into the stable exception type.
    private fun installTranslatingExceptions(combined: PolicyDefinition<PolicyScope.ProcessWideSafe>): ProcessFilterInstaller.InstalledProcessFilter =
        try {
            ProcessFilterInstaller.install(combined)
        } catch (expectedFailure: MazewallInstallationException) {
            throw expectedFailure
        } catch (expectedFailure: Exception) {
            throw MazewallInstallationException(
                expectedFailure.message ?: "Failed to install process-wide seccomp lockdown filter",
                expectedFailure,
            )
        }

    private fun wrapOrPreserveLinkageError(linkageError: NoClassDefFoundError): Throwable =
        if (isNativeEngineLinkageError(linkageError)) {
            MazewallInstallationException(
                "Native FFM engine failed to initialize; check --enable-native-access and Linux host support.",
                linkageError,
            )
        } else {
            linkageError
        }

    private fun wrapNativeInitFailure(initError: ExceptionInInitializerError): MazewallInstallationException {
        val rootCause = initError.cause
        val message =
            if (rootCause is IllegalCallerException) {
                "Native FFM access is disabled; start the JVM with " +
                    "--enable-native-access=io.mazewall.lockdown (module path) or " +
                    "--enable-native-access=ALL-UNNAMED (classpath)."
            } else {
                rootCause?.message ?: "Failed to initialize native FFM seccomp engine"
            }
        return MazewallInstallationException(message, initError)
    }

    private fun isNativeEngineLinkageError(error: NoClassDefFoundError): Boolean {
        val detail = error.message.orEmpty()
        return detail.contains("io/mazewall/lockdown/internal/platform/sys/LinuxNative") ||
            detail.contains("io/mazewall/lockdown/internal/ffi/internal/RealNativeEngine") ||
            detail.contains("io.mazewall.lockdown.internal.platform.sys.LinuxNative") ||
            detail.contains("io.mazewall.lockdown.internal.ffi.internal.RealNativeEngine")
    }
}
