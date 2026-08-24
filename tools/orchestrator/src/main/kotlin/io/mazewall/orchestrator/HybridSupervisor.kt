package io.mazewall.orchestrator

import java.net.http.HttpClient
import java.time.Duration
import kotlin.system.exitProcess

/**
 * Deterministic control-plane shim over the Paperclip board.
 *
 * The board owns all lifecycle state (statuses, blockers, recovery actions) and the
 * adapters own agent sessions; this process only makes bounded decisions the board
 * cannot: markdown-backlog dispatch/routing, CI attention signals, and local git
 * resolution when an issue completes. Poll-based by design — every fact is derived
 * from current board/GitHub truth, never from a local shadow state machine.
 */
class HybridSupervisor(
    private val client: PaperclipClient,
    private val router: ComponentRouter,
    private val out: (String) -> Unit = ::println,
    private val err: (String) -> Unit = System.err::println,
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
) {
    fun tick(maxDispatch: Int): Int {
        // External failures degrade to a skipped tick, never a crash (orchestrator rule 4).
        val agents = runCatching { client.listAgents(companyId) }
            .onFailure { err("listAgents failed: ${it.message}"); return 0 }
            .getOrThrow()
        val agentsByUrlKey = agents.associateBy { it.urlKey }
        val agentsByAdapter = agents.associateBy { it.adapterType }

        var dispatched = 0
        while (dispatched < maxDispatch) {
            // Re-select after each assignment: the board changed under us.
            val issues = runCatching { client.listIssues(companyId) }
                .onFailure { err("listIssues failed mid-tick: ${it.message}"); return dispatched }
                .getOrThrow()
            val candidate = DispatchSelector.select(issues) ?: break

            val component = router.componentOf(candidate.description)
            val urlKey = router.urlKeyFor(component)
            val agent = urlKey?.let(agentsByUrlKey::get)
                ?: agentsByAdapter[router.defaultAdapter]
            if (agent == null) {
                err("no roster agent for component '$component' and no '${router.defaultAdapter}' fallback")
                return dispatched
            }

            runCatching {
                client.assignAgent(candidate.id, agent.id)
                client.startProgress(candidate.id)
            }.onFailure {
                err("dispatch failed for ${candidate.identifier}: ${it.message}")
                return dispatched
            }
            out(
                "Dispatched ${candidate.identifier} (component=${component ?: "-"}) " +
                    "-> ${agent.urlKey ?: agent.name ?: agent.id}",
            )
            dispatched++
        }
        return dispatched
    }

    fun runForever() {
        val interval = env("PAPERCLIP_TICK_SECONDS")?.toLongOrNull() ?: 30L
        val max = env("PAPERCLIP_MAX_DISPATCH")?.toIntOrNull() ?: 1
        while (true) {
            tick(max)
            sleepMs(Duration.ofSeconds(interval).toMillis())
        }
    }

    private val companyId: String by lazy { env("PAPERCLIP_COMPANY_ID") ?: client.resolveCompanyId() }

    companion object {
        fun env(key: String): String? = System.getenv(key)?.takeIf { it.isNotBlank() }
    }
}

fun main(args: Array<String>) {
    var dryRun = false
    var daemon = false
    for (arg in args) {
        when (arg) {
            "--dry-run", "-n" -> dryRun = true
            "--daemon" -> daemon = true
            else -> {
                System.err.println("Unknown argument: $arg")
                exitProcess(1)
            }
        }
    }

    val apiKey = HybridSupervisor.env("PAPERCLIP_API_KEY") ?: "local"
    val baseUrl = HybridSupervisor.env("PAPERCLIP_API_URL") ?: "http://127.0.0.1:3100"
    val client = PaperclipClient(
        // HTTP/1.1 forced: the local board is a Node server that closes h2c-upgrade
        // connections mid-handshake ("header parser received no bytes").
        transport = RealHttpTransport(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build(),
        ),
        apiKey = apiKey,
        baseUrl = baseUrl,
    )
    val router = ComponentRouter(
        customRoutes = ComponentRouter.parseOverrides(HybridSupervisor.env("PAPERCLIP_COMPONENT_ROUTES")),
    )
    val supervisor = HybridSupervisor(client, router)

    if (dryRun) {
        val companyId = HybridSupervisor.env("PAPERCLIP_COMPANY_ID") ?: client.resolveCompanyId()
        val byUrlKey = client.listAgents(companyId).associateBy { it.urlKey }
        val byAdapter = client.listAgents(companyId).associateBy { it.adapterType }
        DispatchSelector.ordered(client.listIssues(companyId))
            .take(5)
            .forEach { issue ->
                val component = router.componentOf(issue.description)
                val target = router.urlKeyFor(component)
                    ?.let { byUrlKey[it]?.urlKey }
                    ?: ("adapter:" + router.defaultAdapter + " (" + (byAdapter[router.defaultAdapter]?.urlKey ?: "?") + ")")
                println("  would dispatch ${issue.identifier} component=${component ?: "-"} -> $target")
            }
        return
    }

    if (daemon) {
        supervisor.runForever()
    } else {
        val dispatched = supervisor.tick(HybridSupervisor.env("PAPERCLIP_MAX_DISPATCH")?.toIntOrNull() ?: 1)
        println("Loop tick complete ($dispatched dispatched).")
    }
}
