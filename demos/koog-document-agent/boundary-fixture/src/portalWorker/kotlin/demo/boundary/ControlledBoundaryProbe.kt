package demo.boundary

import java.nio.file.Files
import java.nio.file.Path

class ControlledBoundaryProbe : BoundaryProbeService {
    override fun read(path: String): String = Files.readString(Path.of(path))
    override fun crash(): String { Runtime.getRuntime().halt(73); error("halt returned") }
}
