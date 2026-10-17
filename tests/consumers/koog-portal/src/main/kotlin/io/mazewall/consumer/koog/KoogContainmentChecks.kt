package io.mazewall.consumer.koog

import io.mazewall.portal.PortalInvocationEvidence
import io.mazewall.portal.PortalRemoteCallException
import io.mazewall.portal.PortalRuntime
import java.nio.file.Files
import java.nio.file.Path

internal suspend fun verifyFilesystemContainment(runtime: PortalRuntime, directory: Path) {
    val canary = Files.createTempFile(directory, "koog-canary-", ".txt")
    Files.writeString(canary, "KOOG_SYNTHETIC_CANARY")
    check(Files.readString(canary) == "KOOG_SYNTHETIC_CANARY")
    Files.writeString(canary, "HOST_WRITE_OK")
    Files.writeString(canary, "KOOG_SYNTHETIC_CANARY")
    val alias = Files.createSymbolicLink(canary.resolveSibling("${canary.fileName}.link"), canary.fileName)
    check(Files.readString(alias) == "KOOG_SYNTHETIC_CANARY")

    val read = boundProbe(runtime, "test_read_canary") { service, args -> service.read(args.value) }
    val write = boundProbe(runtime, "test_write_canary") { service, args -> service.write(args.value) }
    for ((probe, path) in listOf(read to canary, write to canary, read to alias)) {
        val failure = expectFailure<PortalRemoteCallException> { probe.execute(ProbeArguments(path.toString())) }
        check(failure.evidence == PortalInvocationEvidence.COMPLETED)
        check(failure.remoteType == "java.nio.file.AccessDeniedException") { "unexpected remote failure: $failure" }
        check("KOOG_SYNTHETIC_CANARY" !in failure.remoteMessage)
        check(Files.readString(canary) == "KOOG_SYNTHETIC_CANARY")
    }
}
