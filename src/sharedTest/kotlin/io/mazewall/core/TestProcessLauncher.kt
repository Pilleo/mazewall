package io.mazewall.platform.lifecycle

import java.nio.file.Files
import java.nio.file.Path

internal object TestProcessLauncher {
    fun startWithLog(
        builder: ProcessBuilder,
        log: Path,
    ): Process =
        // Configure stderr explicitly even when merging it. A default PIPE
        // redirect can leave a parent descriptor for the process reaper.
        builder
            .redirectInput(Path.of("/dev/null").toFile())
            .redirectOutput(log.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectErrorStream(true)
            .start()

    fun startInherited(builder: ProcessBuilder): Process = builder.inheritIO().start()

    fun readLog(log: Path): String = Files.newInputStream(log).use { input -> input.readNBytes(65_536).toString(Charsets.UTF_8) }
}
