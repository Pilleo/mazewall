package io.mazewall.portal.worker

import io.mazewall.portal.PortalServiceManifest

/** Worker-only constructor hook for one verified service implementation. */
public fun interface PortalWorkerFactory {
    /** Called only after worker containment and manifest verification. */
    public fun create(manifest: PortalServiceManifest): Any

    /**
     * Receives the group-local construction context when a factory opts into automatic collaborator
     * construction. Existing explicit factories retain their one-argument behavior.
     */
    public fun create(
        manifest: PortalServiceManifest,
        context: PortalWorkerConstructionContext,
    ): Any = create(manifest)
}
