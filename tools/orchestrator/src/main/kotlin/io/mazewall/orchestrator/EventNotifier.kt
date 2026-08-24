package io.mazewall.orchestrator

import java.util.Properties

/**
 * Board-event → Telegram doorbell, with the smallest possible persisted state:
 * a single watermark Properties file (orchestrator AGENTS.md §3 — Java Properties,
 * no external serialization). Approvals are announced exactly once via their
 * createdAt watermark; once acted on they leave the pending list naturally.
 */
class EventNotifier(
    private val bot: TelegramBot,
    private val client: PaperclipClient,
    private val companyId: String,
    private val stateFile: java.nio.file.Path =
        java.nio.file.Path.of(".supervisor_state.properties"),
) {
    private val props = Properties()

    init {
        runCatching {
            stateFile.toFile().inputStream().use { props.load(it) }
        }
    }

    private var approvalWatermark: String? = props.getProperty(WATERMARK_KEY)

    /** Polls pending approvals; sends a card for each newer than the watermark. */
    fun pollApprovals() {
        val pending = runCatching { client.listPendingApprovals(companyId) }
            .onFailure { System.err.println("notifier: approvals poll failed: ${it.message}") }
            .getOrNull() ?: return

        val watermark = approvalWatermark
        val fresh = pending.filter { approval ->
            val ts = approval.createdAt
            (ts == null && watermark == null) ||
                (ts != null && (watermark == null || ts > watermark))
        }
        for (approval in fresh.sortedBy { it.createdAt.orEmpty() }) {
            bot.sendMessageWithPaperclipApproval(
                approval.id,
                "*Approval Requested*" +
                    (approval.type?.let { "\nType: $it" } ?: "") +
                    "\nOpen the board to inspect, or decide here.",
            )
        }
        fresh.maxOfOrNull { it.createdAt.orEmpty() }?.let {
            approvalWatermark = it
            props.setProperty(WATERMARK_KEY, it)
            save()
        }
    }

    private fun save() {
        stateFile.toFile().outputStream().use { props.store(it, "HybridSupervisor state") }
    }

    companion object {
        const val WATERMARK_KEY = "approvalWatermark"
    }
}
