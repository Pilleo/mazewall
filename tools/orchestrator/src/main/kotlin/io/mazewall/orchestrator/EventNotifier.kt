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

    // Approvals without a parseable createdAt cannot ride the watermark; they are
    // deduplicated in-process only. A restart may re-announce one - acceptable
    // versus permanently silencing it (fail-loud over fail-silent).
    private val announcedWithoutTimestamp = HashSet<String>()

    /** Polls pending approvals; sends a card for each newer than the watermark. */
    fun pollApprovals() {
        val pending = runCatching { client.listPendingApprovals(companyId) }
            .onFailure { System.err.println("notifier: approvals poll failed: ${it.message}") }
            .getOrNull() ?: return

        val watermark = approvalWatermark
        val fresh = pending.filter { approval ->
            val ts = approval.createdAt
            when {
                ts == null -> approval.id !in announcedWithoutTimestamp
                watermark == null || ts > watermark -> true
                else -> false
            }
        }
        var lastDeliveredWatermark: String? = null
        // Ordered oldest-first; the watermark may only advance past approvals whose
        // cards Telegram actually accepted, so transient failures are retried next
        // tick instead of being lost forever (Codex P2, PR #513).
        for (approval in fresh.sortedBy { it.createdAt.orEmpty() }) {
            val delivered = bot.sendMessageWithPaperclipApproval(
                approval.id,
                "*Approval Requested*" +
                    (approval.type?.let { "\nType: $it" } ?: "") +
                    "\nOpen the board to inspect, or decide here.",
            )
            if (!delivered) {
                System.err.println("notifier: Telegram delivery failed; watermark not advanced")
                break
            }
            if (approval.createdAt == null) {
                // No watermark coverage: remember in-process so later ticks in this
                // run do not re-announce. Marked only after successful delivery.
                announcedWithoutTimestamp.add(approval.id)
            } else {
                lastDeliveredWatermark = approval.createdAt
            }
        }
        lastDeliveredWatermark?.let { newWatermark ->
            val watermark = approvalWatermark
            approvalWatermark = if (watermark != null && watermark > newWatermark) watermark else newWatermark
            props.setProperty(WATERMARK_KEY, approvalWatermark)
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
