package io.mazewall.consumer.koog.worker

import io.mazewall.consumer.koog.DeniedResourceService
import java.nio.file.Files
import java.nio.file.Path

/** Test-only protected implementation; never a host fallback or advertised tool. */
public class DeniedResourceProbe : DeniedResourceService {
    override fun read(path: String): String = Files.readString(Path.of(path))

    override fun write(path: String): String {
        Files.writeString(Path.of(path), "WORKER_WRITE")
        return "WROTE"
    }

    override fun crash(): String {
        Runtime.getRuntime().halt(73)
        error("halt returned")
    }

    override fun waitForever(): String {
        while (true) Thread.sleep(60_000)
    }

    override fun oversizedText(): String = "x".repeat(65_537)

    override fun oversizedWire(): String = "x".repeat(131_073)
}
