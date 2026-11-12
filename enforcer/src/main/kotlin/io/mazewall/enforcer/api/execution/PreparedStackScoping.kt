package io.mazewall.enforcer.api.execution

import io.mazewall.ffi.internal.OwnedStackScopingBootstrap

public class PreparedStackScoping internal constructor(
    private val bootstrap: OwnedStackScopingBootstrap,
) : AutoCloseable {
    public override fun close(): Unit = bootstrap.close()
}
