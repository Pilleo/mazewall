package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.PrctlCommand

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
