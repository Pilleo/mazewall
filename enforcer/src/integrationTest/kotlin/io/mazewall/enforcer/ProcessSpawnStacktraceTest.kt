package io.mazewall.enforcer

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.NeedsFreshJvm
import io.mazewall.enforcer.api.Platform
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.StacktraceScopingPolicy
import io.mazewall.enforcer.internal.supervisor.SupervisorInstaller
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.TimeUnit

@EnabledOnOs(OS.LINUX)
@NeedsFreshJvm
class ProcessSpawnStacktraceTest {
    @Test
    fun `execve inherits parent stack trace for policy validation`() {
        if (!Platform.isSupported()) {
            return
        }

        val execveCalled = java.util.concurrent.atomic
            .AtomicBoolean(false)
        val stackTraceCaptured = java.util.concurrent.atomic
            .AtomicReference<List<StackTraceElement>>()

        val handler: (Tid, List<Any>, List<StackTraceElement>) -> Boolean = { _: Tid, _: List<Any>, stack: List<StackTraceElement> ->
            System.err.println("DEBUG: EXECVE/EXECVEAT handler called with stack size: ${stack.size}")
            stackTraceCaptured.set(stack)
            execveCalled.set(true)
            true
        }

        val scopingPolicy = object : StacktraceScopingPolicy {
            override val handlers = mapOf(
                Syscall.EXECVE to handler,
                Syscall.EXECVEAT to handler,
            )
        }

        val policy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .addAction(SeccompAction.ACT_NOTIFY, Syscall.EXECVE)
            .addAction(SeccompAction.ACT_NOTIFY, Syscall.EXECVEAT)
            .allowMmapExec()
            .build()

        var spawnFailure: java.io.IOException? = null
        SupervisorInstaller.installSupervisedFilterForThread(policy.definition, scopingPolicy).use {
            // Initiate a process spawn. The BPF filter should trigger ACT_NOTIFY on vfork/fork/clone
            // because EXECVE is supervised. posix_spawn's child is often unreadable under Yama;
            // fd-based execveat then fail-closes with EPERM instead of CONTINUE on a TOCTOU path.
            val pb = ProcessBuilder("true")
            try {
                val process = pb.start()
                process.waitFor(5, TimeUnit.SECONDS)
            } catch (failure: java.io.IOException) {
                spawnFailure = failure
                // Spawn-child exec is still fail-closed; see issue-20260817-033800.
            }
        }

        assertTrue(execveCalled.get(), "EXECVE handler should have been called; spawn failure: $spawnFailure")
        val stack = stackTraceCaptured.get()
        assertTrue(stack != null && stack.isNotEmpty(), "Stack trace should not be empty")

        // Verify that the stack trace contains ProcessBuilder.start (the parent's context)
        assertTrue(
            stack!!.any { it.methodName == "start" && it.className.contains("ProcessBuilder") },
            "Stack trace should contain ProcessBuilder.start. Actual stack:\n${stack.joinToString("\n")}",
        )
    }
}
