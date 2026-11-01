package io.mazewall.consumer.koog

import io.mazewall.portal.PortalInvocationEvidence
import io.mazewall.portal.PortalRemoteCallException
import io.mazewall.portal.PortalRuntime
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

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

internal suspend fun verifyNetworkAndProcessContainment(runtime: PortalRuntime) {
    ServerSocket().use { listener ->
        listener.soTimeout = 2_000
        listener.bind(InetSocketAddress("127.0.0.1", 0))
        Socket().use { client ->
            client.soTimeout = 2_000
            client.connect(listener.localSocketAddress, 2_000)
            listener.accept().use { accepted ->
                check(accepted.inetAddress.hostAddress == "127.0.0.1" && accepted.port == client.localPort)
            }
        }
        verifyHostJavaVersion()

        val connect = boundProbe(runtime, "test_connect_loopback") { service, _ -> service.connectLoopback(listener.localPort) }
        val network = expectFailure<PortalRemoteCallException> { connect.execute(ProbeArguments("")) }
        check(network.evidence == PortalInvocationEvidence.COMPLETED)
        check(network.remoteType == "java.net.SocketException") { "unexpected remote network failure: $network" }
        check(network.remoteMessage in setOf("Operation not permitted", "Permission denied")) {
            "unexpected remote network diagnostic: $network"
        }

        val spawn = boundProbe(runtime, "test_spawn_java_version") { service, _ -> service.spawnJavaVersion() }
        val process = expectFailure<PortalRemoteCallException> { spawn.execute(ProbeArguments("")) }
        check(process.evidence == PortalInvocationEvidence.COMPLETED)
        check(process.remoteType == "java.io.IOException") { "unexpected remote process failure: $process" }
        check(Regex("""\berror[=:]\s*(1|13)\b""").containsMatchIn(process.remoteMessage)) {
            "unexpected remote process diagnostic: $process"
        }
    }
}

private fun verifyHostJavaVersion() {
    // Discard the fixed version output in a pipe: DISCARD opens /dev/null before spawning.
    val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version")
        .redirectErrorStream(true)
        .start()
    val execution = runCatching {
        process.inputStream.use {
            process.outputStream.use {
                check(process.waitFor(5, TimeUnit.SECONDS)) { "host java -version timed out" }
                check(process.exitValue() == 0) { "host java -version exited ${process.exitValue()}" }
            }
        }
    }
    val cleanup = runCatching {
        if (process.isAlive) process.destroyForcibly()
        check(process.waitFor(5, TimeUnit.SECONDS)) { "host java -version cleanup timed out" }
        check(!process.isAlive) { "host java -version remains alive" }
    }
    execution.exceptionOrNull()?.let { failure ->
        cleanup.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
    cleanup.getOrThrow()
}
