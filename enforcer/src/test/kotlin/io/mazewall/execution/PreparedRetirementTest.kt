package io.mazewall.enforcer.api.execution

import io.mazewall.enforcer.internal.execution.Deadline
import io.mazewall.enforcer.internal.execution.PreparedRetirement
import io.mazewall.ffi.internal.PreparedPath
import io.mazewall.platform.fd.ResourceRetirement
import io.mazewall.platform.native.LinuxNative
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreparedRetirementTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `deadline retains real descriptor until physical worker and stalled validation finish despite cleanup interruption`() {
        val pin = PreparedPath.open(Files.writeString(directory.resolve("resource"), "data"), LinuxNative.snapshot())
        val workerRelease = CountDownLatch(1)
        val validationEntered = CountDownLatch(1)
        val validationRelease = CountDownLatch(1)
        val monitor = Any()
        val worker = Thread.ofPlatform().start { workerRelease.await() }
        val validator = Thread.ofPlatform().start {
            synchronized(monitor) {
                pin.validate()
                validationEntered.countDown()
                validationRelease.await()
            }
        }
        val failure = AtomicReference<Throwable?>()
        val name = "prepared-retirement-real-fd-${pin.descriptor.value}"
        val retirement = PreparedRetirement(name, { worker.join() }, { synchronized(monitor) { pin.close() } }, { failure.set(it) })
        retirement.start()
        try {
            assertTrue(validationEntered.await(2, TimeUnit.SECONDS))
            retirement.request()
            retirement.await(Deadline(Duration.ofMillis(20)))
            assertFalse(retirement.finished())
            assertTrue(pin.descriptor.isLiveForIo())
            workerRelease.countDown()
            worker.join(2000)
            Thread
                .getAllStackTraces()
                .keys
                .single { it.name == name }
                .interrupt()
            retirement.await(Deadline(Duration.ofMillis(20)))
            assertFalse(retirement.finished())
            assertTrue(pin.descriptor.isLiveForIo())
        } finally {
            workerRelease.countDown()
            validationRelease.countDown()
            worker.join(2000)
            validator.join(2000)
            retirement.await(Deadline(Duration.ofSeconds(3)))
        }
        assertTrue(retirement.finished())
        assertFalse(pin.descriptor.isLiveForIo())
        assertNull(failure.get())
    }

    @Test
    fun `fatal cleanup remains primary after independent genuine descriptors all retire`() {
        val first = PreparedPath.open(Files.writeString(directory.resolve("first"), "one"), LinuxNative.snapshot())
        val second = PreparedPath.open(Files.writeString(directory.resolve("second"), "two"), LinuxNative.snapshot())
        val ordinary = IllegalStateException("first close outcome")
        val fatal = AssertionError("second fatal close outcome")
        val observed = AtomicReference<Throwable?>()
        val uncaught = AtomicReference<Throwable?>()
        val name = "prepared-retirement-fatal-${first.descriptor.value}"
        val retirement = PreparedRetirement(name, {}, {
            val fold = ResourceRetirement()
            fold.attempt {
                first.close()
                throw ordinary
            }
            fold.attempt {
                second.close()
                throw fatal
            }
            fold.finish()
        }, { observed.set(it) })
        retirement.start()
        Thread
            .getAllStackTraces()
            .keys
            .single { it.name == name }
            .uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> uncaught.set(error) }
        retirement.request()
        retirement.await(Deadline(Duration.ofSeconds(3)))
        assertTrue(retirement.finished())
        assertFalse(first.descriptor.isLiveForIo())
        assertFalse(second.descriptor.isLiveForIo())
        assertTrue(observed.get() === fatal)
        assertTrue(uncaught.get() === fatal)
        assertTrue(fatal.suppressed.any { it === ordinary })
    }
}
