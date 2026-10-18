---
title: "Make vulnerable web network ceiling evidence locale independent"
severity: "LOW"
status: "open"
priority: medium
dependencies: []
component: "testing"
target_modules:
  - ":demos:vulnerable-web-app"
target_files:
  - "demos/vulnerable-web-app/src/test/kotlin/demo/vulnapp/VulnerableWebAppFreshJvmTest.kt"
verify_cheap:
  - "./gradlew :demos:vulnerable-web-app:test --tests demo.vulnapp.VulnerableWebAppFreshJvmTest"
needs_kernel: true
core_lock: false
effort: "small"
autonomy: "supervised"
open_questions: false
has_side_effects: false
---

# 🟢 [Severity: LOW]: Make vulnerable web network ceiling evidence locale independent

**Context:**
The full build while implementing Koog containment contracts failed in the unchanged `learned network calls cannot relax the explicit service ceiling` case. Its zero-request assertion succeeded, but lines 105-107 recognize only English permission phrases or numeric errno. On this host the real response ended with the Belarusian `дзеяньне не дазволена`, so the fixture correctly refused to call a generic fetch error containment proof, but incorrectly rejected a locale-specific permission diagnostic.

The owning demo, enforcement modules and shared native harness are unchanged from `8b1f8391df5321e606fef9636f45e68edb98b4e4`. The exact focused case passed with only `LC_ALL=C` changed. This follow-up records the existing fixture limitation; no demo/enforcement change is part of the Koog benchmark branch.

**Needed:**
1. Make this fixture's permission evidence locale-independent, or explicitly establish and document its supported test locale at the owning launch boundary. Prefer available typed/structured evidence; do not accept arbitrary I/O errors or generic fetch failures.
2. Preserve the successful outside-worker network control and zero served requests under the required policy, without weakening or bypassing EPERM/EACCES.
3. Verify the actual fresh-JVM case under the affected locale and C locale, recording the launch environment and real denial/control outcomes.

## Investigation
- Initial full build: `.superpowers/sdd/2026-10-18-sandbox-escape-bench/full-build-after-status.log:2613-2628`, exit 1; the owning task ran 57 tests with this sole failure.
- Focused control: `LC_ALL=C ./gradlew :demos:vulnerable-web-app:test --tests '*VulnerableWebAppFreshJvmTest.learned network calls cannot relax the explicit service ceiling*' --console=plain --max-workers=1`, exit 0; retained in that workspace's `locale-control.log` and `.exit`.
- The generic failure is not accepted as a security success in either run. The issue concerns the fixture's recognition boundary, not evidence that the syscall policy was bypassed.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20261018-004800  file: issue-20261018-004800-make-vulnerable-web-network-ceiling-evidence-locale-independ.md -->
