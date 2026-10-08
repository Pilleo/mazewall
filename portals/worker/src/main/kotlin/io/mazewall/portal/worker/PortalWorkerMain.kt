package io.mazewall.portal.worker

import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.InstallationReceipt
import io.mazewall.enforcer.api.ProcessPolicies
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.platform.socket.RealSocketManager
import io.mazewall.portal.Capability
import io.mazewall.portal.PortalChannel
import io.mazewall.portal.PortalFrame
import io.mazewall.portal.PortalMethod
import io.mazewall.portal.PortalPeerClosedException
import io.mazewall.portal.PortalReadTimeoutException
import kotlin.system.exitProcess

/**
 * Placement worker: process-wide policy then RPC. Not USER_NOTIF.
 * Default install is the static fail-closed trio.
 * Pass `--complete-profile <profile-document.json>` to install
 * [io.mazewall.profiler.api.ProfilingResult.dedicatedWorkerPoliciesFromDocument] instead.
 * The file must contain versioned measured complete coverage; plain SBoB and legacy completion
 * markers are rejected.
 * Guest impl is never loaded in the broker process.
 */
public object PortalWorkerMain {
    public const val READY: String = "MAZEWALL_PORTAL_WORKER_READY"

    @JvmStatic
    @Suppress("TooGenericExceptionCaught") // Socket acquisition owns cleanup if channel construction fails, including fatal errors.
    public fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("Usage: PortalWorkerMain <socket_path> [--complete-profile <profile-document.json>]")
            exitProcess(1)
        }
        // Idle workers must not exit on quiet periods: timeouts are an idle tick (continue),
        // while clean peer EOF stops the loop and I/O failures terminate with their cause.
        // The deadline is injectable so tests can prove
        // idle-tick survival in milliseconds instead of minutes
        // (issue-20260824-011654).
        val idleTimeoutMs = System
            .getProperty("io.mazewall.portal.worker.idleTimeoutMs")
            ?.toLongOrNull() ?: 30_000L
        require(idleTimeoutMs > 0) { "portal worker idle timeout must be positive" }
        val sockets = RealSocketManager
        val connected = sockets.connect(args[0])
        val channel = try {
            PortalChannel(connected, sockets)
        } catch (failure: Throwable) {
            runCatching { sockets.close(connected) }.exceptionOrNull()?.takeIf { it !== failure }?.let(failure::addSuppressed)
            throw failure
        }
        val transport = ChannelWorkerTransport(channel, idleTimeoutMs)
        var generation: io.mazewall.portal.PortalWorkerGeneration? = null
        var startupDeadline: io.mazewall.platform.lifecycle.Deadline? = null
        val parentSession = args.indexOf("--parent-session").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        val group = args.indexOf("--worker-group").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        runWorker(
            transport,
            authorizeParent = {
                val sessionIndex = args.indexOf("--parent-session")
                if (sessionIndex >= 0) {
                    require(args.count { it == "--parent-session" } == 1) { "duplicate required parent startup session" }
                    val session = requireNotNull(args.getOrNull(sessionIndex + 1)) { "missing required parent session" }
                    val timeoutIndex = args.indexOf("--parent-timeout-ms")
                    require(timeoutIndex >= 0 && args.count { it == "--parent-timeout-ms" } == 1) { "missing or duplicate parent startup deadline" }
                    val timeout = requireNotNull(args.getOrNull(timeoutIndex + 1)?.toLongOrNull()) { "invalid parent startup deadline" }
                    require(timeout > 0) { "parent startup deadline must be positive" }
                    println("MAZEWALL_PORTAL_PARENT_AUTH_PENDING")
                    System.out.flush()
                    check(!System.out.checkError()) { "parent startup diagnostic publication failed" }
                    val authorizationDeadline = io.mazewall.platform.lifecycle.Deadline
                        .afterMillis(timeout)
                    startupDeadline = authorizationDeadline
                    val permit = io.mazewall.portal.PortalParentAuthorization.accept(
                        channel.receiveControl(authorizationDeadline),
                        session,
                        ProcessHandle.current().pid(),
                    )
                    channel.sendControl(permit.acknowledgement(), authorizationDeadline)
                    generation = captureRequiredGeneration(args, group)
                }
            },
            installContainment = { installWorkerContainment(args) },
            requiredPolicy = { if (parentSession != null) staticWorkerPolicy() else null },
            createDispatcher = {
                val builder = PortalDispatcherTable.Builder()
                bootstrapWorkerDispatchers(
                    builder,
                    System.getProperty("io.mazewall.portal.worker.dispatchers"),
                    PortalWorkerMain::class.java.classLoader,
                    group,
                )
                val dispatcherTable = builder.build()
                PortalWorkerDispatcher { method, payload, fds ->
            when (method) {
                is PortalMethod.Generated -> dispatcherTable.dispatchOrNull(method, payload, fds) ?: throw UnknownPortalMethod(method)
                PortalMethod.Echo,
                PortalMethod.Checksum,
                PortalMethod.Sleep,
                -> PortalBuiltinDispatch.handle(method, payload, fds)
            }
                }
            },
            publishReady = {
                if (parentSession != null) {
                    channel.sendControl(
                        io.mazewall.portal
                        .PortalWorkerReady(parentSession, ProcessHandle.current().pid(), requireNotNull(generation))
                        .frame(),
                            requireNotNull(startupDeadline),
                    )
                } else {
                    println(READY)
                    System.out.flush()
                    check(!System.out.checkError()) { "portal worker readiness publication failed" }
                }
            },
        )
    }

    /** Owns startup until readiness; the driver acquires transport ownership only after publication. */
    @Suppress("TooGenericExceptionCaught") // Startup failure must retire its transport even for fatal guest construction errors.
    internal fun runWorker(
        transport: PortalWorkerTransport,
        installContainment: () -> InstallationReceipt,
        createDispatcher: () -> PortalWorkerDispatcher,
        publishReady: (InstallationReceipt) -> Unit,
        authorizeParent: () -> Unit = {},
        requiredPolicy: () -> io.mazewall.enforcer.api.PolicyDefinition<*>? = { null },
    ) {
        var receipt: InstallationReceipt? = null
        val driver = try {
            authorizeParent()
            receipt = installContainment()
            receipt.requireInstalled()
            receipt.failure?.let { throw it }
            check(receipt.fallback == io.mazewall.enforcer.api.Platform.FallbackBehavior.FAIL) { "worker receipt forbids fallback" }
            check(receipt.processWide && receipt.landlockApplied && receipt.progress?.processWide == true && receipt.progress?.seccompApplied == true && receipt.progress?.landlockApplied == true) {
                "worker requires newly installed process-wide Seccomp and filesystem containment"
            }
            val expectedPolicy = requiredPolicy()
            check(expectedPolicy == null || receipt.requestedPolicy == expectedPolicy) { "installed worker policy does not match static baseline" }
            val initialized = PortalWorkerDriver(transport, createDispatcher())
            publishReady(receipt)
            initialized
        } catch (failure: Throwable) {
            runCatching(transport::close).exceptionOrNull()?.takeIf { it !== failure }?.let(failure::addSuppressed)
            runCatching { receipt?.close() }.exceptionOrNull()?.takeIf { it !== failure }?.let(failure::addSuppressed)
            throw failure
        }
        // Driver owns transport until the request loop physically ends. Receipt outlives that ownership.
        requireNotNull(receipt).use { driver.run() }
    }

    internal fun publishDispatcherTable(raw: String?): PortalDispatcherPublication = PortalDispatcherRegistry.publishStrict(raw)

    @Suppress("SpreadOperator") // The profile decoder deliberately owns the one array forwarded to the vararg installation API.
    internal fun installWorkerContainment(args: Array<String>): InstallationReceipt {
        val profile = completeProfilePath(args)
        if (profile == null) {
            return ContainedExecutors.installOnDedicatedProcess(
                ProcessPolicies.workerFilesystem(RuntimeProfile.HOTSPOT_JIT),
                ProcessPolicies.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT),
                ProcessPolicies.denyNetwork(RuntimeProfile.HOTSPOT_JIT),
            )
        }
        val json = java.nio.file.Files
            .readString(profile)
        return ContainedExecutors.installOnDedicatedProcess(
            *io.mazewall.profiler.api.ProfilingResult
                .dedicatedWorkerPoliciesFromDocument(json),
        )
    }

    private fun staticWorkerPolicy(): io.mazewall.enforcer.api.PolicyDefinition<*> =
        io.mazewall.enforcer.api.PolicyDefinition.combine(
            ProcessPolicies.workerFilesystem(RuntimeProfile.HOTSPOT_JIT).definition,
            ProcessPolicies.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT).definition,
            ProcessPolicies.denyNetwork(RuntimeProfile.HOTSPOT_JIT).definition,
        )

    private fun captureRequiredGeneration(
        args: Array<String>,
        group: String?,
    ): io.mazewall.portal.PortalWorkerGeneration {
        require(completeProfilePath(args) == null) { "broker readiness requires the static worker baseline" }
        require(args.count { it == "--worker-group" } <= 1 && args.count { it == "--worker-bundle" } <= 1) { "duplicate worker generation selector" }
        require("--worker-group" !in args || !group.isNullOrBlank()) { "missing required worker group" }
        val manifests = io.mazewall.portal.PortalServiceManifestCatalog
            .load(PortalWorkerMain::class.java.classLoader)
            .filter { group == null || it.isolationGroup == group }
        require(group == null || manifests.isNotEmpty()) { "required worker group has no real manifest" }
        val bundle = args.indexOf("--worker-bundle").takeIf { it >= 0 }?.let {
            java.nio.file.Path
            .of(requireNotNull(args.getOrNull(it + 1)))
        }
        val actual = io.mazewall.portal.PortalWorkerGeneration
            .capture(System.getProperty("java.class.path"), bundle, manifests)
        val generationIndex = args.indexOf("--worker-generation")
        require(generationIndex >= 0 && args.count { it == "--worker-generation" } == 1) { "missing or duplicate required worker generation" }
        require(args.getOrNull(generationIndex + 1) == "${actual.inputs}:${actual.manifests}:${actual.runtime}") { "actual worker inputs do not match required launch generation" }
        return actual
    }

    internal fun completeProfilePath(args: Array<String>): java.nio.file.Path? {
        val index = args.indexOf("--complete-profile")
        if (index < 0) return null
        require(index + 1 < args.size) { "Usage: PortalWorkerMain <socket_path> [--complete-profile <profile-document.json>]" }
        return java.nio.file.Path
            .of(args[index + 1])
    }
}

private class ChannelWorkerTransport(
    private val channel: PortalChannel,
    private val idleTimeoutMs: Long,
) : PortalWorkerTransport {
    override fun receive(): PortalWorkerReceive = receiveWorkerFrame { channel.receive(idleTimeoutMs) }

    override fun send(frame: io.mazewall.portal.PortalFrame) {
        channel.send(frame)
    }

    override fun close() {
        channel.close()
    }
}

internal fun receiveWorkerFrame(read: () -> Pair<PortalFrame, List<Capability.ReadFd>>): PortalWorkerReceive =
    try {
        val (frame, fds) = read()
        PortalWorkerReceive.Frame(frame, fds)
    } catch (_: PortalReadTimeoutException) {
        PortalWorkerReceive.Idle
    } catch (closed: PortalPeerClosedException) {
        if (closed.suppressed.isEmpty()) PortalWorkerReceive.PeerClosed else PortalWorkerReceive.Failure(closed)
    } catch (failure: java.io.IOException) {
        PortalWorkerReceive.Failure(failure)
    }
