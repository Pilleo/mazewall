---
title: "Keep profiler-native assertions active in release CTest builds"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler-native/tests/binding_manifest_test.cpp"
  - "profiler-native/tests/invocation_registry_test.cpp"
  - "profiler-native/tests/marker_export_test.cpp"
target_symbols:
  - "BindingManifest"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
review_verdict: skipped
has_side_effects: false
---

# 🟡 [Severity: MEDIUM]: Keep profiler-native assertions active in release CTest builds

**Context:**
C/C++ `assert()` macros from`<cassert>`are disabled atcompile timewhen the `NDEBUG` macro isdefined, which isthedefault forCMake Releasebuilds. The `binding_manifest_test.cpp` reliesexclusivelyon `assert()`to validate that`BindingManifest::classify()` correctlymaps JDKnative bindingsignatures to theirexpected`BindingKind`values. In aRelease CTest build, theseassertions areelided, causingthetest binaryto pass silentlyeven when classificationlogic regresses orthemanifestbecomesunsynchronized with HotSpot signatures. Forasecurity-criticalcomponentthatgatesnativemethodinterception, test-timevalidationofbinding classificationmust remain active inall build configurations,including release CTest runs.Thechangemust preservethe fail-closedprinciple: assertionfailures intestsmust causethe test to failvisibly,never besuppressed or convertedto warnings.

**Needed:**
1.In `profiler-native/CMakeLists.txt`,add `target_compile_definitions(binding_manifest_testPRIVATE -UNDEBUG)` afterthe existing`target_compile_options`for`binding_manifest_test` to ensureassertions remain active regardlessof the global`CMAKE_BUILD_TYPE`.
2. Addthesame `-UNDEBUG` definitionto `invocation_registry_test`and `marker_export_test` targets forconsistency acrossall profiler-nativetests.
3.Run `./gradlew :profiler-native:test`toverify that CTestinvokes allthree nativetestexecutables andthat theypass.
4.Temporarily introducea classificationmismatchin `binding_manifest.hpp` (e.g., changea descriptorstring) and confirmthe`binding_manifest_test` fails ina Release build (`-DCMAKE_BUILD_TYPE=Release`),provingassertionsare active.
5. Revert the temporarymismatchand confirmthe test passesagain,then run `./gradlew :profiler:test` to ensure noregression in Kotlin-sideprofiler tests.

## Investigation
- AST identifier scan: 0 hits outside origin files
- profiler-native/tests/binding_manifest_test.cpp
- profiler-native/CMakeLists.txt
- profiler-native/include/binding_manifest.hpp
- BindingManifest::classify

## Important details
- Assertions in profiler-native unittests must neverbe elided,even in Release builds,becausethey validatesecurity-critical bindingclassification logic.
- Thefixmustapplyto all profiler-native test targets, not just `binding_manifest_test`.
- Testfailuresmustbe visibleand causethebuild to fail;theremustbe no silent bypassofassertionfailures.
- Thechange affectsonly testcompilation, not productionlibrary code,so runtimebehavior ofthe agentis unaffected.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260906-205824  file: issue-20260906-205824-keep-profiler-native-assertions-active-in-release-ctest-buil.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
