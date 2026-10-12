package io.mazewall.platform.process

import io.mazewall.platform.lifecycle.MazewallInternal

/** Trusted operator boundary before acquiring the child. The library supplies no environment values. */
@MazewallInternal
public fun interface JvmLaunchPreparation {
    /** Prepare operator-owned launch settings; the command and owned pipe transport must stay intact. */
    public fun prepare(builder: ProcessBuilder)
}
