package io.mazewall.enforcer

class TestServiceViolationMatcher : ViolationMatcher {
    override fun matches(t: Throwable): Boolean {
        val msg = t.message ?: return false
        return msg.contains("SPI_VIOLATION_TRIGGER_KEYWORD")
    }
}
