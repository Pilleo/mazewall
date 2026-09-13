package io.mazewall.portal.codegen

/**
 * The sole entry point for resolving a portal contract before validation,
 * generated wire IDs, manifest schema fingerprints, or source emission.
 */
internal object ResolvedPortalContract {
    fun compile(contract: Class<*>): PortalServiceSchema = PortalServiceSchema.compile(contract)
}
