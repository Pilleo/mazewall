package io.mazewall.seccomp

import io.mazewall.enforcer.api.CompiledSandbox
import io.mazewall.enforcer.api.ProcessPolicyBuilder
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.internal.seccomp.BpfFilter
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import java.nio.file.Files
import java.nio.file.Path

/** KILL_PROCESS probes must run outside the test worker JVM. */
object SeccompAbiProbeChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val nr = args[0].toLong()
        when (args[1]) {
            "baseline" -> Unit
            "guarded" -> {
                val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build()
                PureJavaBpfEngine.install(CompiledSandbox(policy.definition, BpfFilter.build(Arch.current(), policy.definition)))
                Files.writeString(Path.of(args[2]), "installed")
            }
            else -> error("Unknown probe mode: ${args[1]}")
        }
        LinuxNative.raw.syscall(
            nr,
            NativeArg.LongArg(-1),
            NativeArg.NullArg,
            NativeArg.NullArg,
            NativeArg.NullArg,
            NativeArg.NullArg,
            NativeArg.NullArg,
        )
    }
}
