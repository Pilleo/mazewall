package io.mazewall.profiler.tierE.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** Probe-only command harness; production daemon startup exposes no probe modes. */
public object ProbeMain {
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "usage: --probe <command> | --probe-stdin | --probe-cmdfile <file>" }
        val stopRequested = AtomicBoolean(false)
        val service = TierEDaemonService(stopRequested)
        when (args[0]) {
            "--probe" -> println(service.executeCommand(args.drop(1).joinToString(" ")))
            "--probe-stdin" -> generateSequence(::readlnOrNull).forEach { println(service.executeCommand(it)) }
            "--probe-cmdfile" -> {
                require(args.size == 2) { "usage: --probe-cmdfile <file>" }
                Files.readAllLines(Path.of(args[1])).forEach { println(service.executeCommand(it)) }
            }
            else -> error("unknown probe mode: ${args[0]}")
        }
        service.shutdown()
    }
}
