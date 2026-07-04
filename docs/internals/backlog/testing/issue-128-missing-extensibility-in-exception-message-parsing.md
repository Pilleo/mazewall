---
title: "🔴 [Severity: DX-FRICTION]: Missing Extensibility in Exception Message Parsing"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: DX-FRICTION]: Missing Extensibility in Exception Message Parsing

*   **Dimension:** Developer Experience (DX) & API Ergonomics
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/ContainmentViolationDetector.kt`
*   **Failure Hypothesis:** The `ContainmentViolationDetector` is a singleton with a static list of matchers. Users cannot easily add custom detection logic for third-party libraries that wrap IOExceptions with non-standard messages.
*   **Context & Proof:** While `registerMatcher` exists, its interaction with global state (`MATCHERS`) might be problematic in complex, multi-tenant classloaders (e.g., OSGi or certain App Servers).
*   **Cascading Risk Potential:** DX Friction. Users might not be able to rely on `isContainmentViolation` for their specific use cases if they use localized or heavily wrapped exceptions.
*   **Recommendation:** Allow passing an optional configuration or extending the detector via a ServiceLoader pattern for better modularity.
