package io.mazewall.profiler.tierE.tools

/** Export first; only the complete host-side validation may authorize resource retirement. */
public object TierETrustworthySettlement {
    public fun settle(
        owner: TierETrustworthyContainerOwner,
        executionFailure: Throwable? = null,
        verify: () -> Unit,
    ) {
        var failure = executionFailure
        if (failure == null) {
            try {
                owner.export()
                verify()
                owner.retire()
                return
            } catch (error: Throwable) {
                failure = error
            }
        } else if (!owner.exportAttempted) {
            try {
                owner.export()
            } catch (export: Throwable) {
                failure.addSuppressed(export)
            }
        }
        val primary = requireNotNull(failure)
        try {
            owner.retainForRecovery(primary.message.orEmpty())
        } catch (cleanup: Throwable) {
            primary.addSuppressed(cleanup)
        }
        throw primary
    }
}
