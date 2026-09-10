package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.core.PrctlCommand
import io.mazewall.ffi.memory.NativeArena

internal object TraceeAuthorityProbeChild {
    @JvmStatic fun main(args: Array<String>) {
        LinuxNative.process.prctl(PrctlCommand.SetPtracer(args[0].toLong())).getOrThrow("authorize authority probe parent and descendants")
        NativeArena.ofConfined().use { arena ->
            val path = arena.allocateFrom(args[1])
            println("${LinuxNative.process.gettid().value} ${path.address()}")
            System.out.flush()
            System.`in`.read()
        }
    }
}
