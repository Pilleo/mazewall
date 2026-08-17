package io.mazewall

/**
 * Marks USER_NOTIF / daemon protocol types that are not operator API.
 *
 * These types are `public` only because `:enforcer` and `:profiler` are separate
 * Gradle modules; Kotlin `internal` cannot cross that boundary. Do not use them
 * from application code or `io.mazewall.enforcer.api`.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Protocol type, not operator API. Opt in only from mazewall modules.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
)
public annotation class MazewallInternal
