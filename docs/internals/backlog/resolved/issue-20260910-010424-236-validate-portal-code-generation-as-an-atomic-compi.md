---
title: "Validate portal code generation as an atomic compiler"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "portal-codegen"
target_modules: [":portal-codegen", ":portal", ":portal-worker"]
target_files:
  - "portal-codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalServiceSchema.kt"
  - "portal-codegen/src/main/kotlin/io/mazewall/portal/codegen/BoundaryTypes.kt"
  - "portal-codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalStubGenerator.kt"
  - "portal-codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalCodegenPlugin.kt"
target_symbols: ["PortalServiceSchema", "PortalStubGenerator", "GeneratePortalStubsTask"]
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Validate portal code generation as an atomic compiler

**Context:**
The generator inspected only methods declared directly on the requested interface, trusted POJO
constructor parameter names without proving a readable value existed, constructed identifiers from
uncoordinated strings, and wrote directly into persistent output directories. Inherited API could
silently disappear, generated Kotlin could fail on ordinary names, and stale or half-generated
stub/dispatcher pairs could survive configuration changes or failures.

**Needed:**
1. Compile one immutable effective service schema with override, boundary-reader, ID and overload validation.
2. Allocate every generated scope with KotlinPoet naming support.
3. Stage complete stub and dispatcher trees and transactionally replace prior outputs.
4. Compile and execute generated hostile/inherited/POJO fixtures and exercise clean/incremental removal.

**Resolution:** Implemented the validated schema, explicit field/method reader model, stable
root-service wire IDs, independent name allocation and two-tree staged publisher. Gradle TestKit
now compiles generated Kotlin, executes inherited and POJO round trips, verifies case-distinct and
hostile symbols, exercises `[A,B] -> [A] -> []`, and proves a failed schema leaves both published
trees byte-for-byte unchanged. `./gradlew --no-daemon :portal-codegen:check :portal:test
:portal-worker:test :portal:integrationTest` passed. ADK syntax guards passed for all four changed
production Kotlin files; the delivery guard is not configured in `.agent-devkit.json`.

---
<!-- id: issue-20260910-010424-236-validate-portal-code-generation-as-an-atomic-compi  file: issue-20260910-010424-236-validate-portal-code-generation-as-an-atomic-compi.md -->
