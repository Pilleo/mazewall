package io.mazewall.portal.codegen

import io.mazewall.portal.PortalGeneratedNames
import io.mazewall.portal.PortalInstanceMode
import io.mazewall.portal.PortalServiceId
import io.mazewall.portal.PortalServiceManifest
import java.security.MessageDigest
import java.util.Locale

/** Explicit build-time binding between a public contract and a worker implementation. */
public data class PortalServiceRegistration(
    val serviceId: String,
    val contractName: String,
    val implementationName: String,
    val isolationGroup: String,
    val instanceMode: PortalInstanceMode,
    val policyProfile: String,
    val workerArtifact: String,
) : java.io.Serializable

/** Produces metadata without loading or constructing a guest implementation. */
public object PortalManifestGenerator {
    private const val BYTE_MASK: Int = 0xff

    public fun generate(
        contract: Class<*>,
        registration: PortalServiceRegistration,
    ): PortalServiceManifest {
        require(registration.contractName == contract.name) {
            "portal registration ${registration.serviceId} contractName does not match loaded contract ${contract.name}"
        }
        val schema = ResolvedPortalContract.compile(contract)
        return PortalServiceManifest(
            serviceId = PortalServiceId(registration.serviceId),
            contractName = contract.name,
            implementationName = registration.implementationName,
            dispatcherName = PortalGeneratedNames.dispatcherBinaryName(contract),
            schemaId = schemaId(schema),
            isolationGroup = registration.isolationGroup,
            instanceMode = registration.instanceMode,
            policyProfile = registration.policyProfile,
            workerArtifact = registration.workerArtifact,
        )
    }

    private fun schemaId(schema: PortalServiceSchema): String {
        val canonical =
            buildString {
                append(schema.service.name)
                append('\n')
                schema.methods.forEach { method ->
                    append(method.id)
                    append(':')
                    append(method.method.name)
                    append('(')
                    append(method.parameters.joinToString(",") { it.type.typeName })
                    append("):")
                    append(method.returnType.typeName)
                    append('\n')
                }
            }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return "sha256:" + digest.joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and BYTE_MASK) }
    }

    /** Stable generic-aware fingerprint used by build diagnostics before a contract is accepted for generation. */
    internal fun schemaFingerprint(contract: Class<*>): String = schemaId(ResolvedPortalContract.compile(contract))
}
