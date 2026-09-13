package io.mazewall.portal

import java.time.Duration

/** Local measurements for the most recently completed portal invocation. */
public data class PortalInvocationTiming(
    /** Time spent waiting to acquire a worker slot under the call's absolute deadline. */
    val queueWait: Duration,
    /** Time from request dispatch until reply or terminal transport failure. */
    val workerExecution: Duration,
)
