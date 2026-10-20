package io.mazewall.enforcer.diagnostics

import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.ProcessPolicyBuilder
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.lifecycle.MmapFlags
import io.mazewall.platform.lifecycle.MmapProt
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeMapping
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object NativeCompletionProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2)
        val profile = RuntimeProfile.valueOf(args[0])
        require(profile == RuntimeProfile.NATIVE_IMAGE || profile == RuntimeProfile.HOTSPOT_JIT)
        val output = Path.of(args[1])
        val native = LinuxNative.snapshot()
        val slot = CompletionPhaseSlot()
        val policy = ProcessPolicyBuilder.denyList(profile).build()
        val header =
            "profile=${profile.name}\npolicy=denyList:${profile.name}:no-explicit-blocks\npid=${ProcessHandle.current().pid()}\n" +
                "worker_name=mazewall-native-completion-probe\n"
        Files.writeString(
            output.resolve("vm.txt"),
            "runtime=${System.getProperty("java.runtime.version")}\nvm=${System.getProperty("java.vm.name")}\n" +
                ManagementFactory.getRuntimeMXBean().inputArguments.joinToString("\n", postfix = "\n"),
        )
        Files.writeString(output.resolve("journal.txt"), header + "java_worker=0\nnative_tid=0\nphase=CREATED\n")
        // Warm only the concrete native probe path, not a claimed arbitrary JVM closure.
        val warm = native.fileSystem.mmap(0, 4096, MmapProt(3), MmapFlags(0x22), FileDescriptor.ANON, 0)
        NativeMapping(native.raw, warm.getOrThrow("probe warm allocation"), 4096).close()
        Executors.newSingleThreadExecutor { task -> Thread(task, "mazewall-native-completion-probe") }.use { executor ->
            val task =
                Callable {
                    slot.bind(Thread.currentThread().threadId(), native.process.gettid().value)
                    slot.advance(CompletionPhase.INSTALLING)
                    ContainedExecutors.installOnCurrentThread(policy).use { receipt ->
                        slot.advance(CompletionPhase.INSTALL_RETURNED)
                        receipt.requireInstalled()
                        slot.advance(CompletionPhase.INSTALLED)
                    }
                    // Closing the receipt does not remove the irreversible thread filter.
                    slot.advance(CompletionPhase.SCOPE_CLOSED)
                    slot.advance(CompletionPhase.RETURNING)
                    3
                }
            val future = executor.submit(task)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (!future.isDone && System.nanoTime() - deadline < 0) {
                val value = slot.read()
                val staged = output.resolve("journal.next")
                Files.writeString(staged, header + "java_worker=${value.workerId}\nnative_tid=${value.nativeTid}\nphase=${value.phase.name}\n")
                Files.move(staged, output.resolve("journal.txt"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                try {
                    future.get(5, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // A reporting interval elapsed; only the checked get below transfers slot ownership.
                }
            }
            check(future.get(1, TimeUnit.SECONDS) == 3)
            slot.advance(CompletionPhase.COMPLETED)
        }
        slot.advance(CompletionPhase.WORKER_RETIRED)
        val value = slot.read()
        Files.writeString(output.resolve("journal.txt"), header + "java_worker=${value.workerId}\nnative_tid=${value.nativeTid}\nphase=${value.phase.name}\n")
    }
}
