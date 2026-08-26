package io.mazewall.profiler.tierE.stress

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Pure-Kotlin stress driver.
 * Spawns platform threads with unique contexts, makes real syscalls per thread,
 * then exits. Daemon attributes each syscall to the correct context via BPF hash map.
 *
 * Output: one line per declaration "CTX <ctxId> <expectedCount>" on stdout.
 */
public fun main(args: Array<String>) {
    var port = 0
    var workers = 8
    var batches = 200
    var syscallsPerBatch = 50
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> { port = args[i + 1].toInt(); i += 2 }
            "--workers" -> { workers = args[i + 1].toInt(); i += 2 }
            "--batches" -> { batches = args[i + 1].toInt(); i += 2 }
            "--syscalls" -> { syscallsPerBatch = args[i + 1].toInt(); i += 2 }
            else -> i++
        }
    }

    val self = ProcessHandle.current().pid().toInt()
    val sock = Socket(InetAddress.getLoopbackAddress(), port)
    val input = DataInputStream(sock.getInputStream())
    val out = DataOutputStream(sock.getOutputStream())

    fun rpc(cmd: String): String {
        out.writeUTF(cmd)
        out.flush()
        return input.readUTF()
    }

    check(rpc("ATTACH $self").startsWith("OK")) { "attach failed" }
    println("[stress] attached tgid=$self")

    val pool = Executors.newFixedThreadPool(workers)
    val done = CountDownLatch(workers)
    val decls = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, Long>>())

    repeat(workers) { w ->
        pool.submit {
            try {
                val ctxId = 1000 + w
                val tid = ProcessHandle.current().pid().toInt() // placeholder; real TID from native
                // Each batch: set ctx, do N syscalls, clear ctx.
                repeat(batches) {
                    rpc("SET_CTX $tid $ctxId")
                    repeat(syscallsPerBatch) {
                        Files.readAllBytes(Path.of("/proc/self/stat"))
                    }
                    rpc("SET_CTX $tid 0")
                }
                decls.add(ctxId to (batches.toLong() * syscallsPerBatch))
            } finally {
                done.countDown()
            }
        }
    }

    done.await()
    pool.shutdown()
    println("[stress] all workers done")

    for ((ctx, expected) in decls.sortedBy { it.first }) {
        println("CTX $ctx $expected")
    }

    runCatching { rpc("DETACH") }
    sock.close()
}
