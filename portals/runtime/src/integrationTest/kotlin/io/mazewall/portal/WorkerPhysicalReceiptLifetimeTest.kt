package io.mazewall.portal

import io.mazewall.platform.process.JvmChildProcess
import io.mazewall.platform.process.JvmChildSpec
import io.mazewall.platform.process.RealProcessLauncher
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.tools.ToolProvider

class WorkerPhysicalReceiptLifetimeTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `genuine unsupervised receipt remains rooted while held shutdown and guest execute until parent observes reap`() {
        val source = directory.resolve("HeldShutdownWorker.java")
        Files.writeString(
            source,
            """
            package fixture;
            import io.mazewall.enforcer.api.InstallationReceipt;
            import io.mazewall.portal.worker.PortalWorkerMain;
            import io.mazewall.portal.worker.PortalWorkerProcessReceiptLifetime;
            public class HeldShutdownWorker {
                public static void main(String[] args) throws Exception {
                    var install = java.util.Arrays.stream(PortalWorkerMain.class.getMethods()).filter(m -> m.getName().startsWith("installWorkerContainment")).findFirst().orElseThrow();
                    var actual = (InstallationReceipt) install.invoke(PortalWorkerMain.INSTANCE, (Object) new String[0]);
                    actual.requireInstalled();
                    if (!actual.getProcessWide() || !actual.getLandlockApplied() || !actual.getProgress().getSeccompApplied() || actual.getRequestedPolicy().getHasSupervisedSyscalls()) throw new AssertionError("required unsupervised native receipt missing");
                    var owner = new PortalWorkerProcessReceiptLifetime();
                    owner.retain(actual);
                    var field = PortalWorkerProcessReceiptLifetime.class.getDeclaredField("receipt");
                    field.setAccessible(true);
                    var reference = (java.util.concurrent.atomic.AtomicReference<?>) field.get(owner);
                    var shutting = new java.util.concurrent.atomic.AtomicBoolean();
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        shutting.set(true);
                        try {
                            Thread.sleep(1000); // Other shutdown hooks can finish while this guest remains alive.
                            if (reference.get() != actual) throw new AssertionError("actual receipt owner cleared before process end");
                            System.out.println("HELD_SHUTDOWN_ACTUAL_UNSUPERVISED_RECEIPT_RETAINED");
                            System.out.flush();
                            Thread.sleep(30000);
                        } catch (InterruptedException e) { throw new AssertionError(e); }
                    }, "held-guest-shutdown"));
                    new Thread(() -> {
                        while (!shutting.get()) Thread.yield();
                        System.out.println("NONDAEMON_GUEST_EXECUTING_DURING_SHUTDOWN");
                        System.out.flush();
                        try { Thread.sleep(30000); } catch (InterruptedException e) { throw new AssertionError(e); }
                    }, "live-guest").start();
                    owner.executionEnded(actual);
                    System.out.println("GENUINE_REQUIRED_NATIVE_RECEIPT_UNSUPERVISED_NO_LEASE_PROOF");
                    System.out.flush();
                    System.exit(0);
                }
            }
        """.trimIndent(),
        )
        val classes = Files.createDirectory(directory.resolve("classes"))
        val cp = System.getProperty("io.mazewall.portal.worker.classpath")
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-classpath", cp, "-d", classes.toString(), source.toString()))
        val child = JvmChildProcess.start(RealProcessLauncher, JvmChildSpec(mainClass = "fixture.HeldShutdownWorker", classpath = cp + java.io.File.pathSeparator + classes))
        val output = LinkedBlockingQueue<String>(64)
        val pump = Thread.ofPlatform().daemon(true).start {
            child.inputStream.bufferedReader().useLines { lines -> lines.forEach { output.offer(it.take(1024)) } }
        }
        try {
            val observed = mutableListOf<String>()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (observed.none { it == "HELD_SHUTDOWN_ACTUAL_UNSUPERVISED_RECEIPT_RETAINED" } && System.nanoTime() < deadline) {
                output.poll(100, TimeUnit.MILLISECONDS)?.let(observed::add)
            }
            assertTrue(observed.contains("GENUINE_REQUIRED_NATIVE_RECEIPT_UNSUPERVISED_NO_LEASE_PROOF"), observed.toString())
            assertTrue(observed.contains("NONDAEMON_GUEST_EXECUTING_DURING_SHUTDOWN"), observed.toString())
            assertTrue(observed.contains("HELD_SHUTDOWN_ACTUAL_UNSUPERVISED_RECEIPT_RETAINED"), observed.toString())
            assertTrue(child.isAlive, "held guest/hook work is not physical end")
            child.destroyForcibly()
            assertTrue(child.waitFor(5, TimeUnit.SECONDS), "parent must observe physical process reap")
            assertFalse(child.isAlive)
            // This is OS process/native resource reclamation. The static baseline has no
            // supervisor lease; no Java receipt-close or supervised-registration proof is claimed.
        } finally {
            if (child.isAlive) child.destroyForcibly()
            child.waitFor(5, TimeUnit.SECONDS)
            pump.join(5000)
        }
    }
}
