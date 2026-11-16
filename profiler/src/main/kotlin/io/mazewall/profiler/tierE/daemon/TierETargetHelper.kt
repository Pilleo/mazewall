package io.mazewall.profiler.tierE.daemon

import io.mazewall.platform.fd.CleanupFailures
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

private const val HEX_RADIX = 16

internal fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: error("expected register or attach")
    require(args.size % 2 == 1) { "options must have values" }
    val options = linkedMapOf<String, String>()
    args.drop(1).chunked(2).forEach { (key, value) ->
        require(key.startsWith("--") && options.put(key, value) == null) { "invalid or duplicate option: $key" }
    }
    when (command) {
        "register" -> register(options)
        "attach" -> attach(options)
        else -> error("expected register or attach")
    }
}

private fun options(
    values: Map<String, String>,
    vararg keys: String,
) {
    require(values.keys == keys.toSet()) { "expected options: ${keys.joinToString()}" }
}

private fun incarnation(pid: Int): Long {
    require(pid > 0) { "pid must be positive" }
    val stat = Files.readString(Path.of("/proc/$pid/stat"))
    val fields = stat.substringAfterLast(')', "").trim().split(Regex("\\s+"))
    require(fields.firstOrNull() !in setOf("Z", "X", "x")) { "target process has exited" }
    return requireNotNull(TierEProcessIdentityVerifier.parseLinuxIncarnation(stat)) { "malformed proc stat" }
        .also { require(it > 0) { "invalid process incarnation" } }
}

@Suppress("TooGenericExceptionCaught") // Cleanup must retain fatal errors and the primary failure.
internal fun register(
    values: Map<String, String>,
    remove: (Path) -> Unit = Files::delete,
    observe: (Int) -> Long = ::incarnation,
) {
    options(values, "--registration-id", "--pid", "--marker-path", "--session-tag", "--artifact-file", "--output")
    val pid = requireNotNull(values.getValue("--pid").toIntOrNull()) { "invalid pid" }
    val marker = Path.of(values.getValue("--marker-path"))
    val tag = requireNotNull(values.getValue("--session-tag").removePrefix("0x").toIntOrNull(HEX_RADIX)) { "invalid session tag" }
    val start = observe(pid)
    val target = TierERegisteredTarget(
        values.getValue("--registration-id"),
        pid,
        tag,
        start,
        marker,
        ElfSymbolOffset.resolve(marker, "mazewall_stack_marker"),
        values.getValue("--artifact-file"),
    )
    require(target.markerPath.toString().none { it.isWhitespace() || it == '=' || it.isISOControl() }) { "marker path cannot be represented in control v1" }
    require(target.artifactFileName.none { it == '=' || it.isISOControl() } && target.artifactFileName == target.artifactFileName.trim()) {
        "artifact filename cannot be represented in registration v1"
    }
    val destination = Path.of(values.getValue("--output")).toAbsolutePath()
    val temporary = Files.createTempFile(destination.parent, ".tier-e-registration-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    try {
        Files.writeString(
            temporary,
            "version=1\nregistration_id=${target.registrationId}\npid=${target.pid}\nsession_tag=${tag.toString(HEX_RADIX)}\n" +
            "process_incarnation=$start\nmarker_path=$marker\nmarker_offset=${target.markerOffset.toString(HEX_RADIX)}\nartifact_file=${target.artifactFileName}\n",
        )
        require(TierETargetRegistrationFile.read(temporary) == target) { "registration did not roundtrip" }
        require(observe(pid) == start) { "target process identity changed during registration" }
        Files.createLink(destination, temporary)
    } catch (failure: Throwable) {
        try {
            remove(temporary)
        } catch (cleanup: Throwable) {
            throw CleanupFailures.merge(failure, cleanup)
        }
        throw failure
    }
    remove(temporary)
}

private fun attach(values: Map<String, String>) {
    options(values, "--registration", "--socket")
    val target = TierETargetRegistrationFile.read(Path.of(values.getValue("--registration")))
    require(target.markerProofFileName == null) { "only registration v1 is supported" }
    require(target.markerPath.toString().none { it.isWhitespace() || it == '=' || it.isISOControl() }) { "marker path cannot be represented in control v1" }
    require(incarnation(target.pid) == target.processIncarnation) { "registered process identity is stale" }
    val reply = TierETargetControlClient(Path.of(values.getValue("--socket"))).attach(target)
    require(reply == "OK attached pid=${target.pid}") { "ATTACH rejected or invalid reply: $reply" }
    println(reply)
}
