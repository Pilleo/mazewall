package io.mazewall.enforcer

/**
 * Historical catch type for containment violations.
 *
 * The library throws [io.mazewall.enforcer.api.ContainmentViolationException], which
 * **extends this class**. `catch (io.mazewall.enforcer.ContainmentViolationException)`
 * therefore still matches. The opposite inheritance (this class extending the API type)
 * does not: a superclass instance is not an instance of a subclass.
 */
@Deprecated(
    message = "Use io.mazewall.enforcer.api.ContainmentViolationException",
    replaceWith = ReplaceWith(
        "io.mazewall.enforcer.api.ContainmentViolationException",
        "io.mazewall.enforcer.api.ContainmentViolationException",
    ),
)
open class ContainmentViolationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
