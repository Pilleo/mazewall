---
title: "Silent Test Logging with Vocal Output on Failure"
severity: "HIGH"
status: "resolved"
priority: 10
dependencies: []
component: "testing"
effort: "small"
github_issue: 56
---

# 🔴 [Severity: HIGH]: Silent Test Logging with Vocal Output on Failure

**Context:**
Currently, Gradle test execution logs are verbose and output standard streams (`stdout`/`stderr`) and progress markers for all tests regardless of success or failure. In a continuous integration (CI) environment or during local development, this generates significant noise, making it difficult to pinpoint the exact failure location and diagnosis.

**Needed:**
We need to configure all `Test` tasks across the multi-project build to be silent by default (no verbose progress logs or standard streams for passing tests) but highly vocal (printing full exceptions, causes, stack traces, and captured standard output/error) when a test fails.

### Best Practices & Proposed Solution:
1. **Target Area:** Configure `tasks.withType<Test>` in the root `build.gradle.kts` (or subprojects) to adjust the `testLogging` extension.
2. **Conditional Standard Streams:**
   Configure `showStandardStreams = false` by default, but register a listener to capture and dump the standard output/error only when a test fails. For example:
   ```kotlin
   tasks.withType<Test> {
       // Only log failed tests to keep console clean
       testLogging {
           events = setOf(
               org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED
           )
           exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
           showExceptions = true
           showCauses = true
           showStackTraces = true
       }

       // Capture and print standard streams only on failure
       val failedTestsOutputs = mutableMapOf<String, StringBuilder>()

       onOutput(KotlinClosure2<TestDescriptor, TestOutputEvent, Unit>({ descriptor, event ->
           val testId = "${descriptor.className ?: "UnknownClass"}.${descriptor.name}"
           failedTestsOutputs.getOrPut(testId) { StringBuilder() }.append(event.message)
       }))

       afterTest(KotlinClosure2<TestDescriptor, TestResult, Unit>({ descriptor, result ->
           val testId = "${descriptor.className ?: "UnknownClass"}.${descriptor.name}"
           if (result.resultType == org.gradle.api.tasks.testing.TestResult.ResultType.FAILURE) {
               val output = failedTestsOutputs[testId]?.toString()
               if (!output.isNullOrBlank()) {
                   logger.lifecycle("\n=== Captured stdout/stderr for $testId ===")
                   logger.lifecycle(output)
                   logger.lifecycle("===========================================\n")
               }
           }
           failedTestsOutputs.remove(testId)
       }))
   }
   ```
3. **LogLevel Check:**
   If Gradle is run with `--info` or `--debug` log levels, we should respect the user's intent and allow showing all events (passed, skipped, standard streams) to facilitate interactive troubleshooting.
