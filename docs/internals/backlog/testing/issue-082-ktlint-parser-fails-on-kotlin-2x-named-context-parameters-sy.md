---
title: "KtLint parser fails on Kotlin 2.x named context parameters syntax"
severity: "MEDIUM"
status: "open"
priority: 4
dependencies: []
component: "testing"
effort: "medium"
---

# 🟡 [Severity: LOW]: KtLint parser fails on Kotlin 2.x named context parameters syntax

**Context:** To implement compile-time FFM Arena safety, the project uses Kotlin 2.x named context parameters (`context(arena: Arena)`). However, the KtLint Gradle plugin (`org.jlleitschuh.gradle.ktlint` version `14.2.0`) uses an older KtLint engine (even after upgrading to `1.3.1`) that crashes during the AST parsing phase when encountering this new language syntax. The issue affects check/format tasks across `:enforcer`, `:profiler`, and the shared test resources.
**Needed:** Currently bypassed by disabling the KtLint tasks (`enabled = false`) on projects utilizing context parameters. A permanent resolution requires upgrading the KtLint Gradle plugin or KtLint executable to a version that officially supports Kotlin 2.4/2.x context parameters grammar.
