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
    private val ciWatch: CiWatch? = null,
    private val resolver: BacklogResolver? = null,
    private val telegramBot: TelegramBot? = null,
    private val notifier: EventNotifier? = null,
    private val out: (String) -> Unit = ::println,
    private val err: (String) -> Unit = System.err::println,
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
) {
    fun tick(maxDispatch: Int, forceIdentifier: String? = null): Int {
        val allowedLoopAdapters = ALLOWED_LOOP_ADAPTERS + extraLoopAdapters()
        // External failures degrade to a skipped tick, never a crash (orchestrator rule 4).
        val issues = runCatching { client.listIssues(companyId) }
            .onFailure { err("listIssues failed: ${it.message}"); return 0 }
            .getOrThrow()
        // Drain pcapprove/pcreject callbacks; without this the approval buttons
        // render but never fire (Codex P1, PR #513).
        telegramBot?.pollUpdates()
        ciWatch?.tick(issues)
        notifier?.let { n -> runCatching { n.pollApprovals() }.onFailure { err("notifier: ${it.message}") } }
        for (done in issues.filter { it.status == "done" && it.fromMarkdownBacklog }) {
            runCatching { resolver?.resolveIfNeeded(done.identifier ?: done.id, done.description) }
                .onFailure { err("resolve ${done.identifier}: ${it.message}") }
        }
        val agents = runCatching { client.listAgents(companyId) }
            .onFailure { err("listAgents failed: ${it.message}"); return 0 }
            .getOrThrow()
        val agentsByUrlKey = agents.associateBy { it.urlKey }
        val agentsByAdapter = agents.associateBy { it.adapterType }

        var dispatched = 0
        while (dispatched < maxDispatch) {
            // Re-select after each assignment: the board changed under us.
            val fresh = runCatching { client.listIssues(companyId) }
                .onFailure { err("listIssues failed mid-tick: ${it.message}"); return dispatched }
                .getOrThrow()
            val candidate = DispatchSelector.select(fresh, forceIdentifier) ?: break

            val component = router.componentOf(candidate.description)
            val urlKey = router.urlKeyFor(component)
            val agent = urlKey?.let(agentsByUrlKey::get)
                ?: agentsByAdapter[router.defaultAdapter]
            if (agent == null) {
                err("no roster agent for component '$component' and no '${router.defaultAdapter}' fallback")
                return dispatched
            }
            // Operator policy (2026-08-25, tightened): loop work runs on VIBE for all
            // agents, with JULES as the single exception. Any other adapterType is
            // refused unless explicitly unlocked via PAPERCLIP_EXTRA_LOOP_ADAPTERS.
            if (agent.adapterType !in allowedLoopAdapters) {
                err(
                    "REFUSED dispatch of ${candidate.identifier}: adapterType " +
                        "'${agent.adapterType}' is not an approved experiment worker " +
                        "(allowed: ${allowedLoopAdapters}). Unlock via " +
                        "PAPERCLIP_EXTRA_LOOP_ADAPTERS=<type> if the operator permits.",
                )
                return dispatched
            }

            runCatching {
                client.assignAgent(candidate.id, agent.id)
                client.startProgress(candidate.id)
            }.onFailure {
                err("dispatch failed for ${candidate.identifier}: ${it.message}")
                // Recovery: assignment without the in_progress transition strands the
                // issue (selector requires unassigned). Roll the assignment back so a
                // later tick can retry cleanly; best-effort only.
                runCatching { client.unassignAgent(candidate.id) }
                    .onFailure { rErr ->
                        err(
                            "RECOVERY FAILED for ${candidate.identifier}: still assigned+backlog. " +
                                "Manual repair or board-side retry required (${rErr.message})",
                        )
                    }
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
        val ALLOWED_LOOP_ADAPTERS = setOf("vibe", "jules")

        fun env(key: String): String? = System.getenv(key)?.takeIf { it.isNotBlank() }

        fun parseExtra(raw: String?): Set<String> =
            raw.orEmpty()
                ?.split(',')
                ?.map { it.trim().lowercase() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet()

        fun extraLoopAdapters(): Set<String> = parseExtra(env("PAPERCLIP_EXTRA_LOOP_ADAPTERS"))
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
    val resolver = BacklogResolver(repoRoot = java.nio.file.Path.of("").toAbsolutePath(), git = ProcessGitRunner)

    val tgToken = HybridSupervisor.env("TELEGRAM_BOT_TOKEN")
    val tgChat = HybridSupervisor.env("TELEGRAM_CHAT_ID")
    var notifier: EventNotifier? = null
    var telegramBot: TelegramBot? = null
    var notifyHook: (String) -> Unit = {}
    if (tgToken != null && tgChat != null) {
        val bot = TelegramBot(tgToken, tgChat)
        bot.onPaperclipApproval = { action, approvalId, callbackQueryId, messageId ->
            val outcome = runCatching { client.decideApproval(approvalId, action) }
                .map { "Successfully ${action}d" }
                .getOrElse { "${action} failed: ${it.message}" }
            bot.answerCallbackWith(callbackQueryId, outcome)
            messageId?.let { bot.clearReplyMarkup(it) }
        }
        notifier = EventNotifier(bot, client, HybridSupervisor.env("PAPERCLIP_COMPANY_ID") ?: client.resolveCompanyId())
        telegramBot = bot
        notifyHook = { text -> bot.sendMessage(text) }
    }

    val supervisor = HybridSupervisor(
        client, router,
        ciWatch = CiWatch(PaperclipIssueSignals(client), ProcessGhCheckSource(), notify = notifyHook),
        resolver = resolver,
        notifier = notifier,
        telegramBot = telegramBot,
    )

    if (dryRun) {
        val companyId = HybridSupervisor.env("PAPERCLIP_COMPANY_ID") ?: client.resolveCompanyId()
        val byUrlKey = client.listAgents(companyId).associateBy { it.urlKey }
        val byAdapter = client.listAgents(companyId).associateBy { it.adapterType }
        val scoped = client.listIssues(companyId).let { all ->
            val force = HybridSupervisor.env("PAPERCLIP_FORCE_IDENTIFIER")
            if (force.isNullOrBlank()) all else all.filter {
                it.identifier.equals(force, ignoreCase = true)
            }
        }
        DispatchSelector.ordered(scoped)
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
        val dispatched = supervisor.tick(
            HybridSupervisor.env("PAPERCLIP_MAX_DISPATCH")?.toIntOrNull() ?: 1,
            HybridSupervisor.env("PAPERCLIP_FORCE_IDENTIFIER"),
        )
        println("Loop tick complete ($dispatched dispatched).")
    }
}
