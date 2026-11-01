---
title: "Upgrade Gradle 9.8.2 and qualify build compatibility"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "build"
target_modules: ["root-build", "demos/koog-document-agent", "demos/spring-report-api"]
target_files:
  - "gradle/wrapper/gradle-wrapper.properties"
  - "gradle/wrapper/gradle-wrapper.jar"
  - "gradlew"
  - "gradlew.bat"
  - "gradle/verification-metadata.xml"
  - "settings-gradle.lockfile"
  - "buildSrc/gradle/verification-metadata.xml"
  - "buildSrc/gradle.lockfile"
  - "buildSrc/settings-gradle.lockfile"
  - "demos/koog-document-agent/gradle/wrapper/gradle-wrapper.properties"
  - "demos/koog-document-agent/gradle/wrapper/gradle-wrapper.jar"
  - "demos/koog-document-agent/gradlew"
  - "demos/koog-document-agent/gradlew.bat"
  - "demos/spring-report-api/gradle/wrapper/gradle-wrapper.properties"
  - "demos/spring-report-api/gradle/wrapper/gradle-wrapper.jar"
  - "demos/spring-report-api/gradlew"
  - "demos/spring-report-api/gradlew.bat"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Upgrade Gradle 9.8.2 and qualify build compatibility

**Context:**
Pin every tracked executable Gradle wrapper distribution to Gradle 9.8.2 and qualify the
root build configuration plus independent consumer launchers without changing application dependencies.

**Needed:**
1. Use the official binary distribution checksum
   ([Gradle 9.8.2 SHA-256](https://services.gradle.org/distributions/gradle-9.8.2-bin.zip.sha256)).
2. Regenerate root wrapper launchers with Gradle's Wrapper task; keep timeout, URL validation,
   and Gradle-user-home ownership settings intact.
3. Run strict configuration-cache root configuration checks and each independent consumer's
   wrapper/version check, then the full quality gate.

**Decision / result:**
All three wrapper distributions and generated launchers use Gradle 9.8.2. The official Wrapper
task produced the root launcher JAR; its SHA-256 was byte-identical to an independent Wrapper-task
output from the checksum-pinned official distribution. Gradle 9.8.2's embedded Kotlin 2.4.10
required regenerated strict `buildSrc` and root verification metadata plus `buildSrc` locks.
Application and catalog plugin versions stay unchanged; strict verification remains enabled.
Focused build logic, consumer harness, and configuration-cache replay checks passed. Redundant
root-only retry settings were removed after verifying that omission preserves zero retries.

---
<!-- id: issue-20261101-164416-960-upgrade-gradle-9-8-2-and-qualify-build-compatibili  file: issue-20261101-164416-960-upgrade-gradle-9-8-2-and-qualify-build-compatibili.md -->
