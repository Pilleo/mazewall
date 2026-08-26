package io.mazewall.profiler.tierE.daemon

import io.mazewall.profiler.tierE.ffi.PosixFfi
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Test-harness probe modes for the Tier E control plane.
 * These are development/diagnostic entry points only — they are NOT
 * part of the production daemon surface.
 */
public object ProbeMain {

    public fun probeStdin(args: Array<String>) {
        val posix = PosixFfi()
        val fd = posix.connectUnix(args[1])
        if (fd < 0) {
            println("ERR CONNECT")
            exitProcess(1)
        }
        val buf = ByteArray(512)
        while (true) {
            val line = readLine() ?: break
            if (line.isBlank()) continue
            if (!posix.sendAll(fd, (line + "\n").toByteArray())) {
                println("ERR SEND"); break
            }
            val n = posix.recv(fd, buf)
            if (n <= 0) { println("ERR RECV"); break }
            println(String(buf, 0, n).trimEnd('\n'))
        }
        posix.close(fd)
        exitProcess(0)
    }

    public fun probeCmdfile(args: Array<String>) {
        val posix = PosixFfi()
        var fd = -1
        var waited = 0
        while (fd < 0 && waited < 10_000) {
            fd = posix.connectUnix(args[1])
            if (fd < 0) {
                println("WAIT"); System.out.flush(); Thread.sleep(100); waited += 100
            }
        }
        if (fd < 0) { println("ERR CONNECT_TIMEOUT"); exitProcess(1) }
        val cmdFile = Path.of(args[2])
        val outFile = Path.of(args[3])
        val buf = ByteArray(512)
        var consumed = 0
        while (true) {
            val lines = runCatching {
                if (Files.exists(cmdFile)) Files.readAllLines(cmdFile) else emptyList()
            }.getOrDefault(emptyList())
            while (consumed < lines.size) {
                val line = lines[consumed++].trim()
                if (line.isEmpty()) continue
                if (!posix.sendAll(fd, (line + "\n").toByteArray())) {
                    println("ERR SEND"); exitProcess(1)
                }
                val n = posix.recv(fd, buf)
                val reply = if (n <= 0) "ERR RECV" else String(buf, 0, n).trimEnd('\n')
                Files.writeString(
                    outFile, reply + "\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND,
                )
                if (reply.startsWith("ERR RECV") || reply == "OK BYE") {
                    exitProcess(if (reply == "OK BYE") 0 else 1)
                }
            }
            Thread.sleep(25)
        }
    }

    public fun probeSingle(args: Array<String>) {
        val posix = PosixFfi()
        val fd = posix.connectUnix(args[1])
        if (fd < 0) {
            System.err.println("ERR CONNECT"); exitProcess(1)
        }
        val line = args.drop(2).joinToString(" ") + "\n"
        check(posix.sendAll(fd, line.toByteArray())) { "send failed" }
        val buf = ByteArray(512)
        val n = posix.recv(fd, buf)
        val reply = if (n <= 0) "ERR RECV" else String(buf, 0, n).trimEnd('\n')
        println(reply)
        posix.close(fd)
        exitProcess(if (reply.startsWith("OK")) 0 else 1)
    }
}
