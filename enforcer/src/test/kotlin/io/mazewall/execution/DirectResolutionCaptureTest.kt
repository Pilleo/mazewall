package io.mazewall.enforcer.api.execution

import io.mazewall.enforcer.api.CanonicalPolicyArtifact
import io.mazewall.enforcer.api.OperationAdapter
import io.mazewall.enforcer.api.OperationBinding
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.internal.execution.PreparedResourceSet
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.internal.PreparedPath
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.readByte
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.OpenFlags
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.NativeFileSystem
import io.mazewall.platform.native.getFdOrThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Isolated
class DirectResolutionCaptureTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `unconsumed real artifact result keeps carrier and matches existing reviewed metadata`() {
        val root = Files.createDirectory(directory.resolve("associated-classes"))
        val source = Files.writeString(directory.resolve("Selected.java"), "package sample; public final class Selected {}")
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", root.toString(), source.toString()) == 0)
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(root).build()
        val strict = setOf(root.resolve("sample/Selected.class"))
        URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val selected = loader.loadClass("sample.Selected")
            PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, selected, strict).use { old ->
                val owner = DirectResolutionCapture.create()
                try {
                    val result = owner.prepare(RuntimeProfile.HOTSPOT_JIT, policy, selected, strict)
                    try {
                        assertEquals(old.implementationSha256, result.implementationSha256)
                        assertEquals(old.resourceGeneration, result.resourceGeneration)
                        assertSame(selected, result.implementationClass)
                        result.validate()
                        kotlin.test.assertFailsWith<IllegalStateException> { result.resources }
                        kotlin.test.assertFailsWith<IllegalStateException> { result.native }
                        val binding = OperationBinding("test", OperationAdapter.CALLABLE, result.implementationSha256, result.resourceGeneration, mapOf("entryPoint" to "hashCode()I"))
                        kotlin.test.assertFailsWith<IllegalStateException> { result.claim(binding, CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, policy), LinuxNative.snapshot(), 8) }
                        result.validate()
                    } finally {
                        result.close()
                    }
                } finally {
                    assertEquals(DirectResolutionCapture.Retirement.Complete(null), owner.retire())
                }
            }
        }
    }

    @Test
    fun `close stays pending until actual admitted validation exits`() {
        val root = Files.createDirectory(directory.resolve("validation-classes"))
        val source = Files.writeString(directory.resolve("Selected.java"), "package sample; public final class Selected {}")
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", root.toString(), source.toString()) == 0)
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(root).build()
        URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val publicOwner = PreparedResolutionOwner.create()
            val owner = PreparedResolutionOwner::class.java
                .getDeclaredField("capture")
                .apply { isAccessible = true }
                .get(publicOwner) as DirectResolutionCapture
            val result = publicOwner.resolve(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("sample.Selected"), emptySet())
            val field = DirectResolutionCapture::class.java.getDeclaredField("afterValidationPermit").apply { isAccessible = true }
            val original = field.get(owner)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            field.set(owner, {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            })
            val worker = Thread.ofPlatform().start {
                try {
                result.validate()
            } catch (problem: Throwable) {
                failure.set(problem)
            }
            }
            try {
                check(entered.await(5, TimeUnit.SECONDS))
                assertEquals(PreparedResolutionRetirement.Pending(null), publicOwner.retirement())
                kotlin.test.assertFailsWith<PreparedResolutionPendingException> { publicOwner.close() }
                kotlin.test.assertFailsWith<PreparedResolutionPendingException> { result.close() }
                assertEquals(DirectResolutionCapture.Retirement.Pending(null), owner.retire())
            } finally {
                release.countDown()
                worker.join(5000)
                field.set(owner, original)
            }
            check(!worker.isAlive)
            kotlin.test.assertNull(failure.get())
            result.close()
            assertEquals(DirectResolutionCapture.Retirement.Complete(null), owner.retire())
        }
    }

    @Test
    fun `result unknown stays on original carrier and never closes reused descriptor`() {
        val root = Files.createDirectory(directory.resolve("result-unknown"))
        val source = Files.writeString(directory.resolve("Selected.java"), "package sample; public final class Selected {}")
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", root.toString(), source.toString()) == 0)
        val previous = LinuxNative.snapshot().engineIdentity as NativeEngine
        val attempts = AtomicInteger()
        val failure = IllegalStateException("result close unknown")
        val replacement = AtomicReference<FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>?>()
        val native = object : NativeEngine by previous {
            override val fileSystem = object : NativeFileSystem by previous.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    attempts.incrementAndGet()
                    previous.fileSystem.close(fd).getOrThrow("actual result close")
                    replacement.set(previous.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("replacement", FileDescriptorRole.UnixSocket))
                    assertEquals(fd.value, checkNotNull(replacement.get()).value)
                    throw failure
                }
            }
        }
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(root).build()
        LinuxNative.setEngine(native)
        try {
            URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
                val owner = DirectResolutionCapture.create()
                val result = owner.prepare(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("sample.Selected"), emptySet())
                val first = kotlin.test.assertFailsWith<IllegalStateException> { result.close() }
                val second = kotlin.test.assertFailsWith<IllegalStateException> { result.close() }
                kotlin.test.assertNotSame(first, second)
                assertSame(failure, first.cause)
                assertSame(failure, second.cause)
                assertSame(failure, (owner.retire() as DirectResolutionCapture.Retirement.Unknown).history)
                assertEquals(1, attempts.get())
                assertTrue(checkNotNull(replacement.get()).isLiveForIo())
            }
        } finally {
            replacement.get()?.close(previous.fileSystem)
            LinuxNative.setEngine(previous)
        }
    }

    @Test
    fun `result and original carrier settle every exact acquired token only once`() {
        val root = Files.createDirectory(directory.resolve("once-classes"))
        val source = Files.writeString(directory.resolve("Selected.java"), "package sample; public final class Selected {}")
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", root.toString(), source.toString()) == 0)
        val previous = LinuxNative.snapshot().engineIdentity as NativeEngine
        val attempts = java.util.IdentityHashMap<Any, Int>()
        val native = object : NativeEngine by previous {
            override val fileSystem = object : NativeFileSystem by previous.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    attempts[fd] = (attempts[fd] ?: 0) + 1
                    return previous.fileSystem.close(fd)
                }
            }
        }
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(root).build()
        LinuxNative.setEngine(native)
        try {
            URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
                val owner = DirectResolutionCapture.create()
                val result = owner.prepare(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("sample.Selected"), emptySet())
                result.close()
                assertTrue(attempts.isNotEmpty())
                val count = attempts.size
                assertTrue(attempts.values.all { it == 1 })
                result.close()
                assertEquals(DirectResolutionCapture.Retirement.Complete(null), owner.retire())
                assertEquals(count, attempts.size)
                assertTrue(attempts.values.all { it == 1 })
            }
        } finally {
            LinuxNative.setEngine(previous)
        }
    }

    @Test
    fun `failed prepublication keeps original carrier and failure for explicit cleanup`() {
        val root = Files.createDirectory(directory.resolve("prepublication"))
        val source = Files.writeString(directory.resolve("Selected.java"), "package sample; public final class Selected {}")
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", root.toString(), source.toString()) == 0)
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(root).build()
        object : URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()) {}.use { loader ->
            val owner = DirectResolutionCapture.create()
            val failure = kotlin.test.assertFailsWith<IllegalArgumentException> { owner.prepare(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("sample.Selected"), emptySet()) }
            assertSame(failure, (owner.retire() as DirectResolutionCapture.Retirement.Complete).history)
            assertSame(failure, (owner.retire() as DirectResolutionCapture.Retirement.Complete).history)
        }
    }

    @Test
    fun `associated validation rejects changed pinned class bytes`() {
        val root = Files.createDirectory(directory.resolve("changed-classes"))
        val source = Files.writeString(directory.resolve("Selected.java"), "package sample; public final class Selected {}")
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", root.toString(), source.toString()) == 0)
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(root).build()
        URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val owner = DirectResolutionCapture.create()
            val result = owner.prepare(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("sample.Selected"), emptySet())
            try {
                Files.writeString(root.resolve("sample/Selected.class"), "changed")
                kotlin.test.assertFailsWith<IllegalStateException> { result.validate() }
            } finally {
                result.close()
            }
            assertEquals(DirectResolutionCapture.Retirement.Complete(null), owner.retire())
        }
    }

    @Test
    fun `forged result metadata cannot associate with an idle owner`() {
        val root = Files.createDirectory(directory.resolve("forged"))
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(root).build()
        val owner = DirectResolutionCapture.create()
        val fake = DirectResolutionCapture.Metadata(RuntimeProfile.HOTSPOT_JIT, policy.definition, false, "{}", String::class.java, "fake", "fake")
        kotlin.test.assertFailsWith<IllegalStateException> { PreparedOperationResources.direct(owner, fake) }
        assertEquals(DirectResolutionCapture.Retirement.Complete(null), owner.retire())
    }

    @Test
    fun `Kotlin use preserves original body failure and exact new cleanup fatal`() = useFailure(false)

    @Test
    fun `Java try with resources preserves original body failure and exact new cleanup fatal`() = useFailure(true)

    private fun useFailure(java: Boolean) {
        val root = Files.createDirectory(directory.resolve("use-classes"))
        val source = Files.writeString(directory.resolve("Selected.java"), "package sample; public final class Selected {}")
        val helper = Files.writeString(
            directory.resolve("UseHelper.java"),
            "public final class UseHelper { public static void run(AutoCloseable c, RuntimeException e) throws Exception { try (c) { throw e; } } }",
        )
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", root.toString(), source.toString(), helper.toString()) == 0)
        val previous = LinuxNative.snapshot().engineIdentity as NativeEngine
        val fatal = AssertionError("new cleanup fatal")
        val primary = IllegalArgumentException("body failure")
        val attempts = AtomicInteger()
        val native = object : NativeEngine by previous {
            override val fileSystem = object : NativeFileSystem by previous.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    attempts.incrementAndGet()
                    previous.fileSystem.close(fd).getOrThrow("actual close")
                    throw fatal
                }
            }
        }
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(root).build()
        LinuxNative.setEngine(native)
        try {
            URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
                val owner = DirectResolutionCapture.create()
                val result = owner.prepare(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("sample.Selected"), emptySet())
                val observed = if (java) {
                    kotlin.test
                        .assertFailsWith<java.lang.reflect.InvocationTargetException> {
                        loader.loadClass("UseHelper").getMethod("run", AutoCloseable::class.java, RuntimeException::class.java).invoke(null, result, primary)
                    }.cause
                } else {
                    kotlin.test.assertFailsWith<IllegalArgumentException> { result.use { throw primary } }
                }
                assertSame(primary, observed)
                assertTrue(primary.suppressed.any { it === fatal })
                assertSame(fatal, (owner.retire() as DirectResolutionCapture.Retirement.Unknown).history)
                assertEquals(1, attempts.get())
            }
        } finally {
            LinuxNative.setEngine(previous)
        }
    }

    @Test
    fun `Kotlin use never self suppresses a previously delivered capture fatal`() = captureFatalUse(false)

    @Test
    fun `Java try with resources never self suppresses a previously delivered capture fatal`() = captureFatalUse(true)

    private fun captureFatalUse(java: Boolean) {
        val root = Files.createDirectory(directory.resolve("fatal-classes"))
        val source = Files.writeString(directory.resolve("Selected.java"), "package sample; public final class Selected {}")
        val helper = Files.writeString(
            directory.resolve("UseHelper.java"),
            "public final class UseHelper { public static void run(AutoCloseable c, Runnable action) throws Exception { try (c) { action.run(); } } }",
        )
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", root.toString(), source.toString(), helper.toString()) == 0)
        val previous = LinuxNative.snapshot().engineIdentity as NativeEngine
        val fatal = AssertionError("original capture and cleanup fatal")
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val native = object : NativeEngine by previous {
            override val fileSystem = object : NativeFileSystem by previous.fileSystem {
                override fun open(
                    path: ManagedSegment,
                    flags: OpenFlags,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (opens.incrementAndGet() == 2) throw fatal
                    return previous.fileSystem.open(path, flags)
                }

                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    closes.incrementAndGet()
                    previous.fileSystem.close(fd).getOrThrow("actual close")
                    throw fatal
                }
            }
        }
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(root).build()
        LinuxNative.setEngine(native)
        try {
            URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
                val owner = PreparedResolutionOwner.create()
                val action = Runnable { owner.resolve(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("sample.Selected"), emptySet()) }
                val observed = if (java) {
                    kotlin.test
                        .assertFailsWith<java.lang.reflect.InvocationTargetException> {
                        loader.loadClass("UseHelper").getMethod("run", AutoCloseable::class.java, Runnable::class.java).invoke(null, owner, action)
                    }.cause
                } else {
                    kotlin.test.assertFailsWith<AssertionError> { owner.use { action.run() } }
                }
                assertSame(fatal, observed)
                assertEquals(1, fatal.suppressed.size)
                assertSame(fatal, fatal.suppressed.single().cause)
                assertSame(fatal, (owner.retirement() as PreparedResolutionRetirement.Unknown).history)
                assertEquals(1, closes.get())
            }
        } finally {
            LinuxNative.setEngine(previous)
        }
    }

    @Test
    fun `new fatal cleanup is thrown exactly while original fatal history remains first`() {
        val root = Files.createDirectory(directory.resolve("two-fatal-classes"))
        val source = Files.writeString(directory.resolve("Selected.java"), "package sample; public final class Selected {}")
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", root.toString(), source.toString()) == 0)
        val previous = LinuxNative.snapshot().engineIdentity as NativeEngine
        val original = AssertionError("capture fatal")
        val cleanup = AssertionError("new cleanup fatal")
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val native = object : NativeEngine by previous {
            override val fileSystem = object : NativeFileSystem by previous.fileSystem {
                override fun open(
                    path: ManagedSegment,
                    flags: OpenFlags,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (opens.incrementAndGet() == 2) throw original
                    return previous.fileSystem.open(path, flags)
                }

                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    closes.incrementAndGet()
                    previous.fileSystem.close(fd).getOrThrow("actual close")
                    throw cleanup
                }
            }
        }
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(root).build()
        LinuxNative.setEngine(native)
        try {
            URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
                val owner = PreparedResolutionOwner.create()
                assertSame(original, kotlin.test.assertFailsWith<AssertionError> { owner.resolve(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("sample.Selected"), emptySet()) })
                assertSame(cleanup, kotlin.test.assertFailsWith<AssertionError> { owner.close() })
                assertSame(original, (owner.retirement() as PreparedResolutionRetirement.Unknown).history)
                assertTrue(original.suppressed.any { it === cleanup })
                assertEquals(1, closes.get())
            }
        } finally {
            LinuxNative.setEngine(previous)
        }
    }

    @Test
    fun `ordinary resolve failure remains held when new cleanup Error becomes primary`() {
        val root = Files.createDirectory(directory.resolve("ordinary-before-fatal"))
        val source = Files.writeString(directory.resolve("Selected.java"), "package sample; public final class Selected {}")
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", root.toString(), source.toString()) == 0)
        val previous = LinuxNative.snapshot().engineIdentity as NativeEngine
        val original = IllegalStateException("resolve failed")
        val cleanup = AssertionError("cleanup failed")
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val native = object : NativeEngine by previous {
            override val fileSystem = object : NativeFileSystem by previous.fileSystem {
                override fun open(
                    path: ManagedSegment,
                    flags: OpenFlags,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (opens.incrementAndGet() == 2) throw original
                    return previous.fileSystem.open(path, flags)
                }

                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    closes.incrementAndGet()
                    previous.fileSystem.close(fd).getOrThrow("actual close")
                    throw cleanup
                }
            }
        }
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(root).build()
        LinuxNative.setEngine(native)
        try {
            URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
                val owner = PreparedResolutionOwner.create()
                assertSame(original, kotlin.test.assertFailsWith<IllegalStateException> { owner.resolve(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("sample.Selected"), emptySet()) })
                assertSame(cleanup, kotlin.test.assertFailsWith<AssertionError> { owner.close() })
                assertSame(cleanup, (owner.retirement() as PreparedResolutionRetirement.Unknown).history)
                val carrier = PreparedResolutionOwner::class.java
                    .getDeclaredField("capture")
                    .apply { isAccessible = true }
                    .get(owner)
                val prior = carrier.javaClass
                    .getDeclaredField("retainedPreviousHistory")
                    .apply { isAccessible = true }
                    .get(carrier)
                val next = carrier.javaClass
                    .getDeclaredField("retainedCleanupFailure")
                    .apply { isAccessible = true }
                    .get(carrier)
                assertSame(original, prior)
                assertSame(cleanup, next)
                assertTrue(cleanup.suppressed.any { it === original })
                assertSame(cleanup, kotlin.test.assertFailsWith<PreparedResolutionUnknownException> { owner.close() }.cause)
                assertEquals(1, closes.get())
            }
        } finally {
            LinuxNative.setEngine(previous)
        }
    }

    @Test
    fun `caller carrier acquires actual exploded artifact root and selected child`() {
        val root = Files.createDirectory(directory.resolve("classes"))
        val source = Files.writeString(directory.resolve("Selected.java"), "package sample; public final class Selected {}")
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", root.toString(), source.toString()) == 0)
        val child = root.resolve("sample/Selected.class")
        val previous = LinuxNative.snapshot().engineIdentity as NativeEngine
        val paths = mutableListOf<String>()
        val opened = mutableListOf<Pair<String, Long>>()
        val native = object : NativeEngine by previous {
            override val fileSystem = object : NativeFileSystem by previous.fileSystem {
                override fun open(
                    path: ManagedSegment,
                    flags: OpenFlags,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    val bytes = mutableListOf<Byte>()
                    var index = 0L
                    while (true) {
                        val b = path.readByte(index++)
                        if (b == 0.toByte()) break
                        bytes.add(b)
                    }
                    val name = bytes.toByteArray().toString(Charsets.UTF_8)
                    paths.add(name)
                    val result = previous.fileSystem.open(path, flags)
                    if (result is LinuxNative.SyscallResult.Success) opened.add(name to result.value)
                    return result
                }
            }
        }
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(root).build()
        LinuxNative.setEngine(native)
        try {
            URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
                val owner = DirectResolutionCapture.create()
                try {
                    owner.capture(policy.definition, emptySet(), loader.loadClass("sample.Selected"))
                    assertTrue(paths.count { it == root.toRealPath().toString() } >= 2, "grant and independent artifact root must both be acquired")
                    assertTrue(paths.any { it == child.toRealPath().toString() }, "selected child must be acquired")
                    for (target in listOf(root, child)) {
                        val fd = opened.last { it.first == target.toRealPath().toString() }.second
                        assertTrue(Files.isSameFile(Path.of("/proc/self/fd/$fd"), target), "the actual artifact pin must remain open")
                    }
                } finally {
                    assertEquals(DirectResolutionCapture.Retirement.Complete(null), owner.retire())
                }
            }
        } finally {
            LinuxNative.setEngine(previous)
        }
    }

    @Test
    fun `unknown actual descendant close retains artifact root and never closes reused descriptor`() = descendantUnknown(false)

    @Test
    fun `fatal actual descendant close retains original fatal and parent root`() = descendantUnknown(true)

    private fun descendantUnknown(fatal: Boolean) {
        val root = Files.createDirectory(directory.resolve("unknown-tree"))
        val source = Files.writeString(directory.resolve("Selected.java"), "package sample; public final class Selected {}")
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", root.toString(), source.toString()) == 0)
        val child = root.resolve("sample/Selected.class")
        val previous = LinuxNative.snapshot().engineIdentity as NativeEngine
        val rootFd = java.util.concurrent.atomic
            .AtomicLong(-1)
        val childFd = java.util.concurrent.atomic
            .AtomicLong(-1)
        val closes = AtomicInteger()
        val fault: Throwable = if (fatal) AssertionError("fatal descendant close outcome unknown") else IllegalStateException("descendant close outcome unknown")
        val replacement = AtomicReference<FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>?>()
        val native = object : NativeEngine by previous {
            override val fileSystem = object : NativeFileSystem by previous.fileSystem {
                override fun open(
                    path: ManagedSegment,
                    flags: OpenFlags,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    val bytes = mutableListOf<Byte>()
                    var index = 0L
                    while (true) {
                        val b = path.readByte(index++)
                        if (b == 0.toByte()) break
                        bytes.add(b)
                    }
                    val name = bytes.toByteArray().toString(Charsets.UTF_8)
                    val result = previous.fileSystem.open(path, flags)
                    if (result is LinuxNative.SyscallResult.Success) {
                        if (name == root.toRealPath().toString()) rootFd.set(result.value)
                        if (name == child.toRealPath().toString()) childFd.set(result.value)
                    }
                    return result
                }

                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    closes.incrementAndGet()
                    val result = previous.fileSystem.close(fd)
                    if (fd.value.toLong() == childFd.get()) {
                        result.getOrThrow("actual child close")
                        replacement.set(previous.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("replacement", FileDescriptorRole.UnixSocket))
                        assertEquals(fd.value, checkNotNull(replacement.get()).value)
                        throw fault
                    }
                    return result
                }
            }
        }
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(root).build()
        LinuxNative.setEngine(native)
        try {
            URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
                val owner = DirectResolutionCapture.create()
                owner.capture(policy.definition, emptySet(), loader.loadClass("sample.Selected"))
                if (fatal) {
                    assertSame(fault, kotlin.test.assertFailsWith<AssertionError> { owner.retire() })
                } else {
                    assertSame(fault, (owner.retire() as DirectResolutionCapture.Retirement.Unknown).history)
                }
                val count = closes.get()
                assertTrue(Files.isSameFile(Path.of("/proc/self/fd/${rootFd.get()}"), root))
                assertSame(fault, (owner.retire() as DirectResolutionCapture.Retirement.Unknown).history)
                assertEquals(count, closes.get())
                assertTrue(checkNotNull(replacement.get()).isLiveForIo())
            }
        } finally {
            replacement.get()?.close(previous.fileSystem)
            LinuxNative.setEngine(previous)
        }
    }

    @Test
    fun `unsupported loader after artifact root acquisition leaves original failure with carrier`() {
        val root = Files.createDirectory(directory.resolve("bad-loader"))
        val source = Files.writeString(directory.resolve("Selected.java"), "package sample; public final class Selected {}")
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", root.toString(), source.toString()) == 0)
        val previous = LinuxNative.snapshot().engineIdentity as NativeEngine
        val roots = AtomicInteger()
        val native = object : NativeEngine by previous {
            override val fileSystem = object : NativeFileSystem by previous.fileSystem {
                override fun open(
                    path: ManagedSegment,
                    flags: OpenFlags,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    val bytes = mutableListOf<Byte>()
                    var index = 0L
                    while (true) {
                        val b = path.readByte(index++)
                        if (b == 0.toByte()) break
                        bytes.add(b)
                    }
                    if (bytes.toByteArray().toString(Charsets.UTF_8) == root.toRealPath().toString()) roots.incrementAndGet()
                    return previous.fileSystem.open(path, flags)
                }
            }
        }
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(root).build()
        LinuxNative.setEngine(native)
        try {
            object : URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()) {}.use { loader ->
                val owner = DirectResolutionCapture.create()
                val failure = kotlin.test.assertFailsWith<IllegalArgumentException> { owner.capture(policy.definition, emptySet(), loader.loadClass("sample.Selected")) }
                assertEquals(2, roots.get(), "grant and artifact root must have been acquired")
                assertSame(failure, (owner.retire() as DirectResolutionCapture.Retirement.Complete).history)
            }
        } finally {
            LinuxNative.setEngine(previous)
        }
    }

    @Test
    fun `caller carrier validates actual finished jar and selected class`() {
        val root = Files.createDirectory(directory.resolve("jar-classes"))
        val source = Files.writeString(directory.resolve("Selected.java"), "package sample; public final class Selected {}")
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", root.toString(), source.toString()) == 0)
        val jar = directory.resolve("selected.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            output.putNextEntry(JarEntry("sample/Selected.class"))
            Files.copy(root.resolve("sample/Selected.class"), output)
            output.closeEntry()
        }
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(jar).build()
        URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val selected = loader.loadClass("sample.Selected")
            val owner = DirectResolutionCapture.create()
            PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, selected, emptySet()).use { old ->
                owner.prepare(RuntimeProfile.HOTSPOT_JIT, policy, selected, emptySet()).use { result ->
                    assertEquals(old.implementationSha256, result.implementationSha256)
                    assertEquals(old.resourceGeneration, result.resourceGeneration)
                    result.validate()
                }
            }
            assertEquals(DirectResolutionCapture.Retirement.Complete(null), owner.retire())
        }
    }

    @Test
    fun `real Boot modified loader bytes reject while carrier retains failure`() {
        val jar = DeploymentPackagingFixture.boot(directory, false, true)
        val output = DeploymentPackagingFixture.launch(jar, "reject-loader", jar.toString(), probeClass = "io.mazewall.enforcer.api.execution.DirectCaptureBootProbe")
        assertTrue(output.contains("DIRECT_REJECTED_LOADER"))
    }

    @Test
    fun `real Boot associated result matches legacy digest and generation`() {
        val jar = DeploymentPackagingFixture.boot(directory, false)
        val output = DeploymentPackagingFixture.launch(jar, "associate", jar.toString(), probeClass = "io.mazewall.enforcer.api.execution.DirectCaptureBootProbe")
        assertTrue(output.contains("DIRECT_BOOT_ASSOCIATED"))
    }

    @Test
    fun `actual descendant acquisition keeps retirement pending until resolver exits`() {
        val root = Files.createDirectory(directory.resolve("pending-tree"))
        val source = Files.writeString(directory.resolve("Selected.java"), "package sample; public final class Selected {}")
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", root.toString(), source.toString()) == 0)
        val child = root.resolve("sample/Selected.class")
        val previous = LinuxNative.snapshot().engineIdentity as NativeEngine
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val native = object : NativeEngine by previous {
            override val fileSystem = object : NativeFileSystem by previous.fileSystem {
                override fun open(
                    path: ManagedSegment,
                    flags: OpenFlags,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    val bytes = mutableListOf<Byte>()
                    var index = 0L
                    while (true) {
                        val b = path.readByte(index++)
                        if (b == 0.toByte()) break
                        bytes.add(b)
                    }
                    val result = previous.fileSystem.open(path, flags)
                    if (bytes.toByteArray().toString(Charsets.UTF_8) == child.toRealPath().toString()) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    return result
                }
            }
        }
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(root).build()
        LinuxNative.setEngine(native)
        try {
            URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
                val owner = DirectResolutionCapture.create()
                val selected = loader.loadClass("sample.Selected")
                val worker = Thread.ofPlatform().start {
                    try {
                    owner.capture(policy.definition, emptySet(), selected)
                } catch (failure: Throwable) {
                    error.set(failure)
                }
                }
                try {
                    check(entered.await(5, TimeUnit.SECONDS))
                    assertEquals(DirectResolutionCapture.Retirement.Pending(null), owner.retire())
                } finally {
                    release.countDown()
                    worker.join(5000)
                }
                check(!worker.isAlive)
                kotlin.test.assertNull(error.get())
                assertEquals(DirectResolutionCapture.Retirement.Complete(null), owner.retire())
            }
        } finally {
            release.countDown()
            LinuxNative.setEngine(previous)
        }
    }

    @Test
    fun `tree validation failure after actual child open retains root and child until explicit retirement`() {
        val root = Files.createDirectory(directory.resolve("changed-tree"))
        val source = Files.writeString(directory.resolve("Selected.java"), "package sample; public final class Selected {}")
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", root.toString(), source.toString()) == 0)
        val child = root.resolve("sample/Selected.class")
        val previous = LinuxNative.snapshot().engineIdentity as NativeEngine
        val rootFd = java.util.concurrent.atomic
            .AtomicLong(-1)
        val childFd = java.util.concurrent.atomic
            .AtomicLong(-1)
        val native = object : NativeEngine by previous {
            override val fileSystem = object : NativeFileSystem by previous.fileSystem {
                override fun open(
                    path: ManagedSegment,
                    flags: OpenFlags,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    val bytes = mutableListOf<Byte>()
                    var index = 0L
                    while (true) {
                        val b = path.readByte(index++)
                        if (b == 0.toByte()) break
                        bytes.add(b)
                    }
                    val name = bytes.toByteArray().toString(Charsets.UTF_8)
                    val result = previous.fileSystem.open(path, flags)
                    if (result is LinuxNative.SyscallResult.Success) {
                        if (name == root.toRealPath().toString()) rootFd.set(result.value)
                        if (name == child.toRealPath().toString()) {
                            childFd.set(result.value)
                            Files.createSymbolicLink(root.resolve("unexpected-link"), child)
                        }
                    }
                    return result
                }
            }
        }
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(root).build()
        LinuxNative.setEngine(native)
        try {
            URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
                val owner = DirectResolutionCapture.create()
                val failure = kotlin.test.assertFailsWith<IllegalArgumentException> { owner.capture(policy.definition, emptySet(), loader.loadClass("sample.Selected")) }
                assertTrue(Files.isSameFile(Path.of("/proc/self/fd/${rootFd.get()}"), root))
                assertTrue(Files.isSameFile(Path.of("/proc/self/fd/${childFd.get()}"), child))
                assertSame(failure, (owner.retire() as DirectResolutionCapture.Retirement.Complete).history)
                kotlin.test.assertFalse(Files.exists(Path.of("/proc/self/fd/${rootFd.get()}")))
                kotlin.test.assertFalse(Files.exists(Path.of("/proc/self/fd/${childFd.get()}")))
            }
        } finally {
            LinuxNative.setEngine(previous)
        }
    }

    @Test
    fun `strict path replacement after open retains original descriptor until owner retirement`() {
        val root = Files.createDirectory(directory.resolve("strict-owner"))
        val strict = Files.writeString(root.resolve("strict"), "original")
        val backup = root.resolve("original-backup")
        val expectedPath = strict.toRealPath().toString()
        val previous = LinuxNative.snapshot().engineIdentity as NativeEngine
        val descriptor = AtomicInteger(-1)
        val mutationFailure = AtomicReference<Throwable?>()
        val closeCalls = AtomicInteger()
        val native = object : NativeEngine by previous {
            override val fileSystem = object : NativeFileSystem by previous.fileSystem {
                override fun open(
                    path: ManagedSegment,
                    flags: OpenFlags,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    val bytes = mutableListOf<Byte>()
                    var offset = 0L
                    while (true) {
                        val byte = path.readByte(offset++)
                        if (byte == 0.toByte()) break
                        bytes.add(byte)
                    }
                    val matches = bytes.toByteArray().toString(Charsets.UTF_8) == expectedPath
                    val result = previous.fileSystem.open(path, flags)
                    if (matches && result is LinuxNative.SyscallResult.Success) {
                        try {
                            descriptor.set(Math.toIntExact(result.value))
                            Files.move(strict, backup)
                            Files.writeString(strict, "replacement")
                        } catch (problem: Throwable) {
                            mutationFailure.set(problem)
                        }
                    }
                    return result
                }

                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (fd.value == descriptor.get()) closeCalls.incrementAndGet()
                    return previous.fileSystem.close(fd)
                }
            }
        }
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(root).build()
        var owner: DirectResolutionCapture? = null
        var retirementStarted = false
        LinuxNative.setEngine(native)
        try {
            val actualOwner = DirectResolutionCapture.create()
            owner = actualOwner
            val failure = kotlin.test.assertFailsWith<IllegalStateException> { actualOwner.capture(policy.definition, setOf(strict)) }
            mutationFailure.get()?.let { throw it }
            assertTrue(failure.message.orEmpty().contains("Prepared filesystem object was replaced"))
            val descriptorPath = Path.of("/proc/self/fd/${descriptor.get()}")
            assertTrue(Files.isSameFile(descriptorPath, backup))
            retirementStarted = true
            val retired = actualOwner.retire() as DirectResolutionCapture.Retirement.Complete
            assertSame(failure, retired.history)
            assertEquals(1, closeCalls.get())
            assertTrue(!Files.exists(descriptorPath))
            assertSame(failure, (actualOwner.retire() as DirectResolutionCapture.Retirement.Complete).history)
            assertEquals(1, closeCalls.get())
        } finally {
            try {
                if (!retirementStarted) owner?.retire()
            } finally {
                LinuxNative.setEngine(previous)
            }
        }
    }

    @Test
    fun `strict path uses original path operations instead of its display string`() {
        val actual = Files.writeString(directory.resolve("actual"), "content")
        val path = java.lang.reflect.Proxy.newProxyInstance(Path::class.java.classLoader, arrayOf(Path::class.java)) { _, method, arguments ->
            if (method.name == "toString") {
                directory.resolve("nonexistent-display-only").toString()
            } else {
                method.invoke(actual, *(arguments ?: emptyArray()))
            }
        } as Path
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(actual).build()
        PreparedResourceSet.capture(policy.definition, LinuxNative.snapshot(), setOf(path)).use { it.validate() }
        val owner = DirectResolutionCapture.create()
        owner.capture(policy.definition, setOf(path))
        assertEquals(DirectResolutionCapture.Retirement.Complete(null), owner.retire())
    }

    @Test
    fun `caller owner captures real explicit strict and automatic resources and retires once`() {
        val file = Files.writeString(directory.resolve("input"), "content")
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(file).build()
        val owner = DirectResolutionCapture.create()
        owner.capture(policy.definition, setOf(file))
        assertEquals(DirectResolutionCapture.Retirement.Complete(null), owner.retire())
        assertEquals(DirectResolutionCapture.Retirement.Complete(null), owner.retire())
    }

    @Test
    fun `close during real in-flight open waits for resolver physical exit`() {
        val previous = LinuxNative.snapshot().engineIdentity as NativeEngine
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val native = object : NativeEngine by previous {
            override val fileSystem = object : NativeFileSystem by previous.fileSystem {
                override fun open(
                    path: ManagedSegment,
                    flags: OpenFlags,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    val result = previous.fileSystem.open(path, flags)
                    if (calls.incrementAndGet() == 1) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    return result
                }
            }
        }
        val file = Files.writeString(directory.resolve("inflight"), "content")
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(file).build()
        val error = AtomicReference<Throwable?>()
        LinuxNative.setEngine(native)
        val owner = DirectResolutionCapture.create()
        val worker = Thread.ofPlatform().start {
            try {
            owner.capture(policy.definition, setOf(file))
        } catch (failure: Throwable) {
            error.set(failure)
        }
        }
        try {
            check(entered.await(5, TimeUnit.SECONDS))
            assertEquals(DirectResolutionCapture.Retirement.Pending(null), owner.retire())
            release.countDown()
            worker.join(5000)
            check(!worker.isAlive)
            kotlin.test.assertNull(error.get())
            assertEquals(DirectResolutionCapture.Retirement.Complete(null), owner.retire())
        } finally {
            release.countDown()
            worker.join(5000)
            LinuxNative.setEngine(previous)
        }
    }

    @Test
    fun `unknown dispatched close never replays after real descriptor reuse`() {
        val previous = LinuxNative.snapshot().engineIdentity as NativeEngine
        val attempts = AtomicInteger()
        val fault = IllegalStateException("actual close returned unknown")
        val replacement = AtomicReference<FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>?>()
        val native = object : NativeEngine by previous {
            override val fileSystem = object : NativeFileSystem by previous.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    attempts.incrementAndGet()
                    previous.fileSystem.close(fd).getOrThrow("actual close")
                    val next = previous.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("replacement", FileDescriptorRole.UnixSocket)
                    assertEquals(fd.value, next.value)
                    replacement.set(next)
                    throw fault
                }
            }
        }
        val file = Files.writeString(directory.resolve("unknown"), "content")
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(file).build()
        LinuxNative.setEngine(native)
        try {
            val owner = DirectResolutionCapture.create()
            owner.capture(policy.definition, setOf(file))
            assertSame(fault, (owner.retire() as DirectResolutionCapture.Retirement.Unknown).history)
            assertSame(fault, (owner.retire() as DirectResolutionCapture.Retirement.Unknown).history)
            assertEquals(1, attempts.get())
            assertTrue(checkNotNull(replacement.get()).isLiveForIo())
        } finally {
            replacement.get()?.close(previous.fileSystem)
            LinuxNative.setEngine(previous)
        }
    }

    @Test
    fun `settled rollback retains original strict validation failure`() {
        val allowed = Files.writeString(directory.resolve("allowed"), "ok")
        val forbidden = Files.writeString(directory.resolve("forbidden"), "no")
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(allowed).build()
        val owner = DirectResolutionCapture.create()
        val original = kotlin.test.assertFailsWith<IllegalArgumentException> { owner.capture(policy.definition, setOf(forbidden)) }
        assertSame(original, (owner.retire() as DirectResolutionCapture.Retirement.Complete).history)
        assertSame(original, (owner.retire() as DirectResolutionCapture.Retirement.Complete).history)
    }

    @Test
    fun `fatal dispatched close is rethrown and retained without replay`() {
        val previous = LinuxNative.snapshot().engineIdentity as NativeEngine
        val attempts = AtomicInteger()
        val fatal = AssertionError("fatal close result")
        val native = object : NativeEngine by previous {
            override val fileSystem = object : NativeFileSystem by previous.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    attempts.incrementAndGet()
                    previous.fileSystem.close(fd).getOrThrow("actual close")
                    throw fatal
                }
            }
        }
        val file = Files.writeString(directory.resolve("fatal"), "content")
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(file).build()
        LinuxNative.setEngine(native)
        try {
            val owner = DirectResolutionCapture.create()
            owner.capture(policy.definition, setOf(file))
            assertSame(fatal, kotlin.test.assertFailsWith<AssertionError> { owner.retire() })
            assertSame(fatal, (owner.retire() as DirectResolutionCapture.Retirement.Unknown).history)
            assertEquals(1, attempts.get())
        } finally {
            LinuxNative.setEngine(previous)
        }
    }

    @Test
    fun `concurrent retirement stays pending while the original close is in flight`() {
        val previous = LinuxNative.snapshot().engineIdentity as NativeEngine
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val native = object : NativeEngine by previous {
            override val fileSystem = object : NativeFileSystem by previous.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (calls.incrementAndGet() == 1) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    return previous.fileSystem.close(fd)
                }
            }
        }
        val file = Files.writeString(directory.resolve("concurrent"), "content")
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(file).build()
        LinuxNative.setEngine(native)
        val owner = DirectResolutionCapture.create()
        val result = AtomicReference<DirectResolutionCapture.Retirement?>()
        var worker: Thread? = null
        try {
            owner.capture(policy.definition, setOf(file))
            worker = Thread.ofPlatform().start { result.set(owner.retire()) }
            check(entered.await(5, TimeUnit.SECONDS))
            assertEquals(DirectResolutionCapture.Retirement.Pending(null), owner.retire())
            assertEquals(1, calls.get())
            release.countDown()
            worker.join(5000)
            check(!worker.isAlive)
            assertEquals(DirectResolutionCapture.Retirement.Complete(null), result.get())
        } finally {
            release.countDown()
            worker?.join(5000)
            LinuxNative.setEngine(previous)
        }
    }

    @Test
    fun `forged slot with the original snapshot cannot acquire or close`() {
        val owner = DirectResolutionCapture.create()
        val source = DirectResolutionCapture::class.java
            .getDeclaredField("native")
            .apply { isAccessible = true }
            .get(owner) as NativeEngine
        val forged = PreparedPath.DirectSlot(owner, source, directory.resolve("never-created"))
        kotlin.test.assertFailsWith<IllegalStateException> { forged.open() }
        kotlin.test.assertFailsWith<IllegalStateException> { forged.closeOwned() }
        assertEquals(DirectResolutionCapture.Retirement.Complete(null), owner.retire())
    }
}

object DirectCaptureBootProbe {
    @JvmStatic
    fun run(
        selected: Class<*>,
        arguments: Array<String>,
    ) {
        val jar = Path.of(arguments[1])
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(jar).build()
        val owner = DirectResolutionCapture.create()
        if (arguments[0] == "associate") {
            PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, selected, emptySet()).use { old ->
                owner.prepare(RuntimeProfile.HOTSPOT_JIT, policy, selected, emptySet()).use { result ->
                    assertEquals(old.implementationSha256, result.implementationSha256)
                    assertEquals(old.resourceGeneration, result.resourceGeneration)
                    result.validate()
                }
            }
            assertEquals(DirectResolutionCapture.Retirement.Complete(null), owner.retire())
            println("DIRECT_BOOT_ASSOCIATED")
            return
        }
        val failure = kotlin.test.assertFailsWith<IllegalArgumentException> { owner.capture(policy.definition, emptySet(), selected) }
        assertTrue(checkNotNull(failure.message).contains("Unsupported Boot loader implementation bytes"))
        assertSame(failure, (owner.retire() as DirectResolutionCapture.Retirement.Complete).history)
        println("DIRECT_REJECTED_LOADER")
    }
}
