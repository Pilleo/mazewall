package io.mazewall.lockdown

/**
 * Thrown when process-wide seccomp lockdown cannot be verified or installed.
 */
public class MazewallInstallationException : RuntimeException {
    public constructor(message: String) : super(message)

    public constructor(message: String, cause: Throwable?) : super(message, cause)
}
