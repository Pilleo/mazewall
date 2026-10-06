package io.mazewall.enforcer.api

/** Whether unspecified syscalls are permitted (deny-list) or denied (allow-list). JVM floor rules still apply. */
public enum class PolicyMode {
    DENY_LIST,
    ALLOW_LIST,
}
