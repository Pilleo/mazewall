package io.mazewall.orchestrator

import java.util.Properties

class SlotContext(var currentIssueId: String) {
    var state: OrchestratorState = SelectTaskState
    var currentIssueTitle: String? = null
    var currentIssueFile: String? = null
    var githubIssueNumber: String? = null
    var julesSessionId: String? = null
    var prNumber: String? = null

    // Monitoring state/cache variables
    var lastHeadSha: String? = null
    var lastReviewedSha: String? = null
    var lastRequestedReviewSha: String? = null
    var lastBuildStatus: String? = null
    var lastCheckedSha: String? = null
    var lastWaitingLogTime: Long = 0L
    var lastStatusChangeTime: Long = 0L
    var lastKnownStatus: String? = null
    var lastPendingNotificationTime: Long = 0L
    var lastFailedSha: String? = null
    var startTime: Long = 0L
    var julesRetries: Int = 0
    var julesReviewPushCount: Int = 0
    var julesReviewAttemptCount: Int = 0
    var retryAfterTime: Long = 0L
    var julesSessionFailureWaitAttempts: Int = 0
    var julesTriggerAttempts: Int = 0
    var prMergeStatusAttempts: Int = 0
    var approvalRequestSent: Boolean = false
    var failedRebaseHeadSha: String? = null

    fun load(props: Properties, prefix: String) {
        currentIssueTitle = props.getProperty("$prefix.currentIssueTitle").takeIf { !it.isNullOrEmpty() }
        currentIssueFile = props.getProperty("$prefix.currentIssueFile").takeIf { !it.isNullOrEmpty() }
        githubIssueNumber = props.getProperty("$prefix.githubIssueNumber").takeIf { !it.isNullOrEmpty() }
        julesSessionId = props.getProperty("$prefix.julesSessionId").takeIf { !it.isNullOrEmpty() }
        prNumber = props.getProperty("$prefix.prNumber").takeIf { !it.isNullOrEmpty() }

        lastHeadSha = props.getProperty("$prefix.lastHeadSha").takeIf { !it.isNullOrEmpty() }
        lastReviewedSha = props.getProperty("$prefix.lastReviewedSha").takeIf { !it.isNullOrEmpty() }
        lastRequestedReviewSha = props.getProperty("$prefix.lastRequestedReviewSha").takeIf { !it.isNullOrEmpty() }
        lastBuildStatus = props.getProperty("$prefix.lastBuildStatus").takeIf { !it.isNullOrEmpty() }
        lastCheckedSha = props.getProperty("$prefix.lastCheckedSha").takeIf { !it.isNullOrEmpty() }
        lastWaitingLogTime = props.getProperty("$prefix.lastWaitingLogTime")?.toLongOrNull() ?: 0L
        lastStatusChangeTime = props.getProperty("$prefix.lastStatusChangeTime")?.toLongOrNull() ?: 0L
        lastKnownStatus = props.getProperty("$prefix.lastKnownStatus").takeIf { !it.isNullOrEmpty() }
        lastPendingNotificationTime = props.getProperty("$prefix.lastPendingNotificationTime")?.toLongOrNull() ?: 0L
        lastFailedSha = props.getProperty("$prefix.lastFailedSha").takeIf { !it.isNullOrEmpty() }
        startTime = props.getProperty("$prefix.startTime")?.toLongOrNull() ?: 0L
        julesRetries = props.getProperty("$prefix.julesRetries")?.toIntOrNull() ?: 0
        julesReviewPushCount = props.getProperty("$prefix.julesReviewPushCount")?.toIntOrNull() ?: 0
        julesReviewAttemptCount = props.getProperty("$prefix.julesReviewAttemptCount")?.toIntOrNull() ?: 0
        retryAfterTime = props.getProperty("$prefix.retryAfterTime")?.toLongOrNull() ?: 0L
        julesSessionFailureWaitAttempts = props.getProperty("$prefix.julesSessionFailureWaitAttempts")?.toIntOrNull() ?: 0
        julesTriggerAttempts = props.getProperty("$prefix.julesTriggerAttempts")?.toIntOrNull() ?: 0
        prMergeStatusAttempts = props.getProperty("$prefix.prMergeStatusAttempts")?.toIntOrNull() ?: 0

        val stateName = props.getProperty("$prefix.state")
        state = OrchestratorState.fromSlot(this, stateName)
    }

    fun save(props: Properties, prefix: String) {
        props.setProperty("$prefix.state", state.name)
        props.setProperty("$prefix.currentIssueId", currentIssueId)
        props.setProperty("$prefix.currentIssueTitle", currentIssueTitle ?: "")
        props.setProperty("$prefix.currentIssueFile", currentIssueFile ?: "")
        props.setProperty("$prefix.githubIssueNumber", githubIssueNumber ?: "")
        props.setProperty("$prefix.julesSessionId", julesSessionId ?: "")
        props.setProperty("$prefix.prNumber", prNumber ?: "")

        props.setProperty("$prefix.lastHeadSha", lastHeadSha ?: "")
        props.setProperty("$prefix.lastReviewedSha", lastReviewedSha ?: "")
        props.setProperty("$prefix.lastRequestedReviewSha", lastRequestedReviewSha ?: "")
        props.setProperty("$prefix.lastBuildStatus", lastBuildStatus ?: "")
        props.setProperty("$prefix.lastCheckedSha", lastCheckedSha ?: "")
        props.setProperty("$prefix.lastWaitingLogTime", lastWaitingLogTime.toString())
        props.setProperty("$prefix.lastStatusChangeTime", lastStatusChangeTime.toString())
        props.setProperty("$prefix.lastKnownStatus", lastKnownStatus ?: "")
        props.setProperty("$prefix.lastPendingNotificationTime", lastPendingNotificationTime.toString())
        props.setProperty("$prefix.lastFailedSha", lastFailedSha ?: "")
        props.setProperty("$prefix.startTime", startTime.toString())
        props.setProperty("$prefix.julesRetries", julesRetries.toString())
        props.setProperty("$prefix.julesReviewPushCount", julesReviewPushCount.toString())
        props.setProperty("$prefix.julesReviewAttemptCount", julesReviewAttemptCount.toString())
        props.setProperty("$prefix.retryAfterTime", retryAfterTime.toString())
        props.setProperty("$prefix.julesSessionFailureWaitAttempts", julesSessionFailureWaitAttempts.toString())
        props.setProperty("$prefix.julesTriggerAttempts", julesTriggerAttempts.toString())
        props.setProperty("$prefix.prMergeStatusAttempts", prMergeStatusAttempts.toString())
    }
}

class OrchestratorContext {
    var state: OrchestratorState = SelectTaskState
    var currentIssueId: String? = null
    var currentIssueTitle: String? = null
    var currentIssueFile: String? = null
    var githubIssueNumber: String? = null
    var julesSessionId: String? = null
    var prNumber: String? = null
    val skippedIds: MutableSet<String> = mutableSetOf()

    // Monitoring state/cache variables
    var lastHeadSha: String? = null
    var lastReviewedSha: String? = null
    var lastRequestedReviewSha: String? = null
    var lastBuildStatus: String? = null
    var lastCheckedSha: String? = null
    var lastWaitingLogTime: Long = 0L
    var lastStatusChangeTime: Long = 0L
    var lastKnownStatus: String? = null
    var lastPendingNotificationTime: Long = 0L
    var lastFailedSha: String? = null
    var startTime: Long = 0L
    var julesRetries: Int = 0
    var julesReviewPushCount: Int = 0
    var julesReviewAttemptCount: Int = 0
    var retryAfterTime: Long = 0L
    var julesSessionFailureWaitAttempts: Int = 0
    var julesTriggerAttempts: Int = 0
    var prMergeStatusAttempts: Int = 0

    val activeSlots = mutableListOf<SlotContext>()

    fun load(props: Properties) {
        currentIssueId = props.getProperty("currentIssueId").takeIf { !it.isNullOrEmpty() }
        currentIssueTitle = props.getProperty("currentIssueTitle").takeIf { !it.isNullOrEmpty() }
        currentIssueFile = props.getProperty("currentIssueFile").takeIf { !it.isNullOrEmpty() }
        githubIssueNumber = props.getProperty("githubIssueNumber").takeIf { !it.isNullOrEmpty() }
        julesSessionId = props.getProperty("julesSessionId").takeIf { !it.isNullOrEmpty() }
        prNumber = props.getProperty("prNumber").takeIf { !it.isNullOrEmpty() }

        skippedIds.clear()
        props.getProperty("skippedIds")?.let { ids ->
            if (ids.isNotEmpty()) {
                skippedIds.addAll(ids.split(","))
            }
        }

        lastHeadSha = props.getProperty("lastHeadSha").takeIf { !it.isNullOrEmpty() }
        lastReviewedSha = props.getProperty("lastReviewedSha").takeIf { !it.isNullOrEmpty() }
        lastRequestedReviewSha = props.getProperty("lastRequestedReviewSha").takeIf { !it.isNullOrEmpty() }
        lastBuildStatus = props.getProperty("lastBuildStatus").takeIf { !it.isNullOrEmpty() }
        lastCheckedSha = props.getProperty("lastCheckedSha").takeIf { !it.isNullOrEmpty() }
        lastWaitingLogTime = props.getProperty("lastWaitingLogTime")?.toLongOrNull() ?: 0L
        lastStatusChangeTime = props.getProperty("lastStatusChangeTime")?.toLongOrNull() ?: 0L
        lastKnownStatus = props.getProperty("lastKnownStatus").takeIf { !it.isNullOrEmpty() }
        lastPendingNotificationTime = props.getProperty("lastPendingNotificationTime")?.toLongOrNull() ?: 0L
        lastFailedSha = props.getProperty("lastFailedSha").takeIf { !it.isNullOrEmpty() }
        startTime = props.getProperty("startTime")?.toLongOrNull() ?: 0L
        julesRetries = props.getProperty("julesRetries")?.toIntOrNull() ?: 0
        julesReviewPushCount = props.getProperty("julesReviewPushCount")?.toIntOrNull() ?: 0
        julesReviewAttemptCount = props.getProperty("julesReviewAttemptCount")?.toIntOrNull() ?: 0
        retryAfterTime = props.getProperty("retryAfterTime")?.toLongOrNull() ?: 0L
        julesSessionFailureWaitAttempts = props.getProperty("julesSessionFailureWaitAttempts")?.toIntOrNull() ?: 0
        julesTriggerAttempts = props.getProperty("julesTriggerAttempts")?.toIntOrNull() ?: 0
        prMergeStatusAttempts = props.getProperty("prMergeStatusAttempts")?.toIntOrNull() ?: 0

        val stateName = props.getProperty("state")
        state = OrchestratorState.fromContext(this, stateName)

        activeSlots.clear()
        val activeIdsStr = props.getProperty("activeSlots")
        if (!activeIdsStr.isNullOrEmpty()) {
            val ids = activeIdsStr.split(",")
            for (id in ids) {
                if (id.isNotEmpty()) {
                    val slot = SlotContext(id)
                    slot.load(props, "slot.$id")
                    activeSlots.add(slot)
                }
            }
        } else {
            // Check for legacy single-task state
            val legacyState = props.getProperty("state")
            val legacyId = props.getProperty("currentIssueId")
            if (!legacyState.isNullOrEmpty() && !legacyId.isNullOrEmpty()) {
                val slot = SlotContext(legacyId)
                slot.state = OrchestratorState.fromContext(this, legacyState)
                slot.currentIssueTitle = currentIssueTitle
                slot.currentIssueFile = currentIssueFile
                slot.githubIssueNumber = githubIssueNumber
                slot.julesSessionId = julesSessionId
                slot.prNumber = prNumber

                slot.lastHeadSha = lastHeadSha
                slot.lastReviewedSha = lastReviewedSha
                slot.lastRequestedReviewSha = lastRequestedReviewSha
                slot.lastBuildStatus = lastBuildStatus
                slot.lastCheckedSha = lastCheckedSha
                slot.lastWaitingLogTime = lastWaitingLogTime
                slot.lastStatusChangeTime = lastStatusChangeTime
                slot.lastKnownStatus = lastKnownStatus
                slot.lastPendingNotificationTime = lastPendingNotificationTime
                slot.lastFailedSha = lastFailedSha
                slot.startTime = startTime
                slot.julesRetries = julesRetries
                slot.julesReviewPushCount = julesReviewPushCount
                slot.julesReviewAttemptCount = julesReviewAttemptCount
                slot.retryAfterTime = retryAfterTime
                slot.julesSessionFailureWaitAttempts = julesSessionFailureWaitAttempts
                slot.julesTriggerAttempts = julesTriggerAttempts
                slot.prMergeStatusAttempts = prMergeStatusAttempts
                activeSlots.add(slot)
            }
        }
    }

    fun save(props: Properties) {
        props.setProperty("state", state.name)
        props.setProperty("currentIssueId", currentIssueId ?: "")
        props.setProperty("currentIssueTitle", currentIssueTitle ?: "")
        props.setProperty("currentIssueFile", currentIssueFile ?: "")
        props.setProperty("githubIssueNumber", githubIssueNumber ?: "")
        props.setProperty("julesSessionId", julesSessionId ?: "")
        props.setProperty("prNumber", prNumber ?: "")
        props.setProperty("skippedIds", skippedIds.joinToString(","))

        props.setProperty("lastHeadSha", lastHeadSha ?: "")
        props.setProperty("lastReviewedSha", lastReviewedSha ?: "")
        props.setProperty("lastRequestedReviewSha", lastRequestedReviewSha ?: "")
        props.setProperty("lastBuildStatus", lastBuildStatus ?: "")
        props.setProperty("lastCheckedSha", lastCheckedSha ?: "")
        props.setProperty("lastWaitingLogTime", lastWaitingLogTime.toString())
        props.setProperty("lastStatusChangeTime", lastStatusChangeTime.toString())
        props.setProperty("lastKnownStatus", lastKnownStatus ?: "")
        props.setProperty("lastPendingNotificationTime", lastPendingNotificationTime.toString())
        props.setProperty("lastFailedSha", lastFailedSha ?: "")
        props.setProperty("startTime", startTime.toString())
        props.setProperty("julesRetries", julesRetries.toString())
        props.setProperty("julesReviewPushCount", julesReviewPushCount.toString())
        props.setProperty("julesReviewAttemptCount", julesReviewAttemptCount.toString())
        props.setProperty("retryAfterTime", retryAfterTime.toString())
        props.setProperty("julesSessionFailureWaitAttempts", julesSessionFailureWaitAttempts.toString())
        props.setProperty("julesTriggerAttempts", julesTriggerAttempts.toString())
        props.setProperty("prMergeStatusAttempts", prMergeStatusAttempts.toString())

        props.setProperty("activeSlots", activeSlots.map { it.currentIssueId }.joinToString(","))
        for (slot in activeSlots) {
            slot.save(props, "slot.${slot.currentIssueId}")
        }
    }

    fun clearActiveTask() {
        currentIssueId = null
        currentIssueTitle = null
        currentIssueFile = null
        githubIssueNumber = null
        julesSessionId = null
        prNumber = null
        lastHeadSha = null
        lastReviewedSha = null
        lastRequestedReviewSha = null
        lastBuildStatus = null
        lastCheckedSha = null
        lastWaitingLogTime = 0L
        lastStatusChangeTime = 0L
        lastKnownStatus = null
        lastPendingNotificationTime = 0L
        lastFailedSha = null
        startTime = 0L
        julesRetries = 0
        julesReviewPushCount = 0
        julesReviewAttemptCount = 0
        retryAfterTime = 0L
        julesSessionFailureWaitAttempts = 0
        julesTriggerAttempts = 0
        prMergeStatusAttempts = 0
        activeSlots.clear()
    }
}
