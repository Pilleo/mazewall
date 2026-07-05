package io.mazewall.enforcer

import io.mazewall.LinuxNative
import io.mazewall.Policy
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import io.mazewall.core.Tid
import io.mazewall.enforcer.supervisor.PendingSpawnRegistry
import io.mazewall.enforcer.supervisor.StacktraceScopingPolicy
import io.mazewall.enforcer.supervisor.SupervisorInstaller
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.TimeUnit
import java.io.File
import io.mazewall.Platform

@EnabledOnOs(OS.LINUX)
class ProcessSpawnStacktraceTest {

    @Test
    fun `execve inherits parent stack trace for policy validation`() {
        if (!Platform.isSupported()) {
            return
        }

        val execveCalled = java.util.concurrent.atomic.AtomicBoolean(false)
        val stackTraceCaptured = java.util.concurrent.atomic.AtomicReference<List<StackTraceElement>>()

        val scopingPolicy = object : StacktraceScopingPolicy {
            override val handlers = mapOf(
                Syscall.EXECVE to { tid: Tid, args: List<Any>, stack: List<StackTraceElement> ->
                    System.err.println("DEBUG: EXECVE handler called with stack size: ${stack.size}")
                    stackTraceCaptured.set(stack)
                    execveCalled.set(true)
                    true
                }
            )
        }

        val policy = Policy.builder()
            .addAction(SeccompAction.ACT_NOTIFY, Syscall.EXECVE)
            .build()

        SupervisorInstaller.installSupervisedFilterForThread(policy.definition, scopingPolicy).use {
            // Initiate a process spawn. The BPF filter should trigger ACT_NOTIFY on vfork/fork/clone
            // because EXECVE is supervised.
            val pb = ProcessBuilder("true")
            val process = pb.start()
            process.waitFor(5, TimeUnit.SECONDS)
        }

        assertTrue(execveCalled.get(), "EXECVE handler should have been called")
        val stack = stackTraceCaptured.get()
        assertTrue(stack != null && stack.isNotEmpty(), "Stack trace should not be empty")

        // Verify that the stack trace contains ProcessBuilder.start (the parent's context)
        assertTrue(stack!!.any { it.methodName == "start" && it.className.contains("ProcessBuilder") },
            "Stack trace should contain ProcessBuilder.start. Actual stack:\n${stack.joinToString("\n")}")
    }
}
