package io.mazewall.portal

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** Application-level acquisition ownership; the broker still owns its process/native lifecycle. */
internal class PortalBrokerOwnership<T : AutoCloseable>(
    private val closeTimeoutMs: Long,
) : AutoCloseable {
    private class Entry<T : AutoCloseable> {
        val ready = CompletableFuture<T>()
        val acquired = CountDownLatch(1)
        var resource: T? = null
        var retired = false

        fun retire() =
            synchronized(this) {
            if (!retired) {
                resource?.let {
                it.close()
                retired = true
            }
            }
        }
    }

    private class CloseCycle {
        val done = CountDownLatch(1)

        @Volatile var failure: Throwable? = null
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry<T>>()
    private var phase = PortalOwnershipPhase.OPEN
    private var closing: CloseCycle? = null

    init {
        require(closeTimeoutMs > 0L)
    }

    fun isAccepting(): Boolean = synchronized(lock) { phase == PortalOwnershipPhase.OPEN }

    fun assertOpen() {
        if (!isAccepting()) throw PortalRuntimeClosedException()
    }

    fun ready(key: String): T? =
        synchronized(lock) {
        entries[key]?.ready?.let { if (it.isCompletedExceptionally) null else it.getNow(null) }
    }

    @Suppress("TooGenericExceptionCaught") // acquisition boundary owns rollback and preserves fatal identity
    fun acquire(
        key: String,
        factory: () -> T,
        start: (T) -> Unit,
    ): T {
        var creator = false
        val entry = synchronized(lock) {
            if (phase != PortalOwnershipPhase.OPEN) throw PortalRuntimeClosedException()
            entries[key] ?: Entry<T>().also {
                entries[key] = it
                creator = true
            }
        }
        if (creator) create(entry, factory, start)
        return awaitReady(entry)
    }

    @Suppress("TooGenericExceptionCaught") // owned startup always rolls back and retains original fatal/ordinary failure
    private fun create(entry: Entry<T>, factory: () -> T, start: (T) -> Unit) {
            try {
                val resource = factory()
                synchronized(entry) { entry.resource = resource }
                assertOpen()
                start(resource)
                synchronized(lock) {
                    if (phase != PortalOwnershipPhase.OPEN) throw PortalRuntimeClosedException()
                    entry.ready.complete(resource)
                }
            } catch (failure: Throwable) {
                var primary = failure
                try {
                    entry.retire()
                } catch (cleanup: Throwable) {
                    primary = rollbackFailure(failure, cleanup)
                }
                entry.ready.completeExceptionally(primary)
                throw primary
            } finally {
                entry.acquired.countDown()
            }
    }

    private fun awaitReady(entry: Entry<T>): T {
        return try {
            entry.ready.get()
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        }
    }

    @Suppress("TooGenericExceptionCaught") // close attempts every owner, retains failures for retry and rethrows fatal first
    override fun close() {
        var closer = false
        val cycle = synchronized(lock) {
            when (phase) {
                PortalOwnershipPhase.CLOSED -> return
                PortalOwnershipPhase.CLOSING -> checkNotNull(closing)
                PortalOwnershipPhase.OPEN, PortalOwnershipPhase.FAILED -> CloseCycle().also {
                    closing = it
                    phase = PortalRuntimeOwnershipLifecycle.evaluate(phase, PortalOwnershipEvent.CLOSE_REQUESTED)
                    closer = true
                }
            }
        }
        if (!closer) {
            await(cycle.done)
            cycle.failure?.let { throw it }
            return
        }
        val owners = synchronized(lock) { entries.values.toList() }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(closeTimeoutMs)
        val failures = ArrayList<Throwable>()
        val attempted = HashSet<Entry<T>>()
        try {
            retireAvailable(owners, attempted, failures)
            joinAcquisitions(owners, attempted, failures, deadline)
            val primary = aggregate(failures)
            cycle.failure = primary
            publishClose(owners, primary)
            primary?.let { throw it }
        } finally {
            cycle.done.countDown()
        }
    }

    private fun retireAvailable(
        owners: List<Entry<T>>,
        attempted: MutableSet<Entry<T>>,
        failures: MutableList<Throwable>,
    ) {
        owners.forEach { entry ->
            if (synchronized(entry) { entry.resource != null }) {
                attempted.add(entry)
                retire(entry, failures)
            }
        }
    }

    private fun joinAcquisitions(
        owners: List<Entry<T>>,
        attempted: Set<Entry<T>>,
        failures: MutableList<Throwable>,
        deadline: Long,
    ) {
        owners.forEach { entry ->
            try {
                if (!entry.acquired.await((deadline - System.nanoTime()).coerceAtLeast(0L), TimeUnit.NANOSECONDS)) {
                    failures.add(PortalIsolationException("Portal runtime close could not join pending broker acquisition"))
                } else if (entry !in attempted) {
                    retire(entry, failures)
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                failures.add(interrupted)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // each resource retirement retains failures for exact later retry
    private fun retire(entry: Entry<T>, failures: MutableList<Throwable>) {
        try {
            entry.retire()
        } catch (failure: Throwable) {
            failures.add(failure)
        }
    }

    private fun aggregate(failures: List<Throwable>): Throwable? {
        val distinct = failures.distinctBy { IdentityFailure(it) }
        val primary = distinct.firstOrNull { it is Error } ?: distinct.firstOrNull()
        distinct.filter { it !== primary }.forEach { primary?.addSuppressed(it) }
        return primary
    }

    private fun publishClose(
        owners: List<Entry<T>>,
        failure: Throwable?,
    ) = synchronized(lock) {
        if (failure == null && owners.all { it.acquired.count == 0L && synchronized(it) { it.resource == null || it.retired } }) {
            entries.clear()
            phase = PortalRuntimeOwnershipLifecycle.evaluate(phase, PortalOwnershipEvent.CLOSE_COMPLETED)
        } else {
            phase = PortalRuntimeOwnershipLifecycle.evaluate(phase, PortalOwnershipEvent.CLOSE_FAILED)
        }
    }

    private fun await(latch: CountDownLatch) {
        try {
            check(latch.await(closeTimeoutMs, TimeUnit.MILLISECONDS)) { "Portal runtime close is still in progress" }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        }
    }

    private fun rollbackFailure(
        startup: Throwable,
        cleanup: Throwable,
    ): Throwable {
        if (startup === cleanup) return startup
        return if (cleanup is Error && startup !is Error) {
            cleanup.addSuppressed(startup)
            cleanup
        } else {
            startup.addSuppressed(cleanup)
            startup
        }
    }

    private class IdentityFailure(
        private val failure: Throwable,
    ) {
        override fun equals(other: Any?): Boolean = other is IdentityFailure && other.failure === failure

        override fun hashCode(): Int = System.identityHashCode(failure)
    }
}
