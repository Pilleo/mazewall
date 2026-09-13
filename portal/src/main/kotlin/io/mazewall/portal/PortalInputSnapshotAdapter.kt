package io.mazewall.portal

/**
 * Opts one inbound [java.io.InputStream] parameter into bounded copy-in/copy-out
 * transport. The caller owns the original stream; the worker receives a fresh,
 * finite stream backed by the captured bytes.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class PortalInputSnapshotAdapter(
    /** Maximum number of caller bytes copied for this parameter. */
    val maximumBytes: Int,
)
