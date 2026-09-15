package io.mazewall.profiler.tierE.stress

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pure-Kotlin stress driver.
 * Spawns platform threads with unique contexts, makes real syscalls per thread,
 * then exits. Daemon attributes each syscall to the correct context via BPF hash map.
 *
 * Output: one line per declaration "CTX <ctxId> <expectedCount>" on stdout.
 */
internal fun main(args: Array<String>) {
    var port = 0
    var workers = 8
    var batches = 200
    var syscallsPerBatch = 50
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> {
                port = args[i + 1].toInt()
                i += 2
            }
            "--workers" -> {
                workers = args[i + 1].toInt()
                i += 2
            }
            "--batches" -> {
                batches = args[i + 1].toInt()
                i += 2
            }
            "--syscalls" -> {
                syscallsPerBatch = args[i + 1].toInt()
                i += 2
            }
            else -> i++
        }
    }

    val self = ProcessHandle.current().pid().toInt()
    StressRpc.connect(port).use { rpc ->
        check(rpc.call("ATTACH $self").startsWith("OK")) { "attach failed" }
        println("[stress] attached tgid=$self")
        runWorkers(rpc, workers, batches, syscallsPerBatch)
    }
}

private fun runWorkers(
    rpc: StressRpc,
    workers: Int,
    batches: Int,
    syscallsPerBatch: Int,
) {
    val pool = Executors.newFixedThreadPool(workers)
    try {
        val done = CountDownLatch(workers)
        val decls = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, Long>>())
        repeat(workers) { worker ->
            pool.submit {
                try {
                    val contextId = 1000 + worker
                    val tid = ProcessHandle.current().pid().toInt() // placeholder; real TID from native
                    repeat(batches) {
                        rpc.call("SET_CTX $tid $contextId")
                        repeat(syscallsPerBatch) { Files.readAllBytes(Path.of("/proc/self/stat")) }
                        rpc.call("SET_CTX $tid 0")
                    }
                    decls.add(contextId to (batches.toLong() * syscallsPerBatch))
                } finally {
                    done.countDown()
                }
            }
        }
        done.await()
        println("[stress] all workers done")
        decls.sortedBy { it.first }.forEach { (contextId, expected) -> println("CTX $contextId $expected") }
    } finally {
        pool.shutdownNow()
        val terminated = pool.awaitTermination(5, TimeUnit.SECONDS)
        runCatching { rpc.call("DETACH") }
        check(terminated) { "stress workers did not terminate" }
    }
}

private class StressRpc private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
) : AutoCloseable {
    @Synchronized
    fun call(command: String): String {
        output.writeUTF(command)
        output.flush()
        return input.readUTF()
    }

    override fun close() {
        output.close()
        input.close()
        socket.close()
    }

    companion object {
        fun connect(port: Int): StressRpc {
            val socket = Socket(InetAddress.getLoopbackAddress(), port)
            return StressRpc(socket, DataInputStream(socket.getInputStream()), DataOutputStream(socket.getOutputStream()))
        }
    }
}
