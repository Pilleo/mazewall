package io.mazewall.enforcer.api

import io.mazewall.platform.native.NativeStackContextProvider
import io.mazewall.platform.native.NativeStackContexts

/** Explicit opt-in only; the ordinary mock continues to advertise no live capability. */
internal class MockNativeStackContexts(
    override val stackContexts: NativeStackContexts,
) : MockNativeEngine(),
    NativeStackContextProvider
