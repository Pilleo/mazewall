---
title: "Align orchestrator backlog validation with active Tier-E metadata"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BacklogValidator.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/BacklogValidatorTest.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/IssueTemplateGenerator.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/IssueTemplateGeneratorTest.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BacklogConstants.kt"
target_symbols:
  - "BacklogValidator"
verify_cheap:
  - "./gradlew :tools:orchestrator:test --tests io.mazewall.orchestrator.BacklogValidatorTest"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
review_verdict: approved
has_side_effects: true
---

# 🟡 [Severity: MEDIUM]: Align orchestrator backlog validation with active Tier-E metadata

**Context:**
The orchestrator modulecurrentlysuffersfrom duplicatedvalidation constantsacross BacklogValidator.kt andIssueTemplateGenerator.kt. Bothfiles maintainindependent copiesof VALID_COMPONENTS,VALID_SEVERITIES, andmodule allowlists.The resolved backlog item issue-20260826-122817explicitlyidentifiesthis duplication as theroot cause of validationdrift,noting that thehardcoded moduleallowlist in BacklogValidator.kt:12 doesnot match settings.gradle.kts andblocksCI.The idealremedyperthatitemis to derive constantsfrom settings or establisha single source oftruth with CIdrift detection.

**Needed:**
1.Create BacklogConstants.kt in tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/with shared VALID_COMPONENTS, VALID_GRADLE_MODULES, VALID_SEVERITIES, VALID_STATUSES aspublic constants
2. Update BacklogValidator.ktto import and useconstantsfrom BacklogConstantsinsteadof privatevals
3.Update IssueTemplateGenerator.kt to import anduse constants from BacklogConstants andremoveits duplicateVALID_SEVERITIES and VALID_COMPONENTS
4. AddOrchestratorConstantsTestto verify constantvalues remaininsync withsettings.gradle.kts
5. AddCI drift check thatcomparesVALID_GRADLE_MODULES against settings.gradle.kts includes

## Investigation
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BacklogValidator.kt
- tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/BacklogValidatorTest.kt
- settings.gradle.kts
- docs/internals/backlog/testing/issue-20260825-193000-tier-e-r3-golden-protocol-conformance.md
- docs/internals/backlog/code_health/issue-20260826-122817-fix-broken-gradle-build-for-fresh-clones-missing-tie.md
- docs/internals/designs/profiler/tier-e-design.md
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/IssueTemplateGenerator.kt:87
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/IssueTemplateGenerator.kt:174-188
- tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/ReviewIssueLauncherTest.kt:51
- tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/IssueTemplateGeneratorTest.kt
- docs/internals/backlog/resolved/issue-20260826-122817-fix-broken-gradle-build-for-fresh-clones-missing-tie.md:32
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BacklogValidator.kt:8-10
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BacklogValidator.kt:67-80
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/IssueTemplateGenerator.kt:169-176
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/IssueTemplateGenerator.kt:58-88
- profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerConstants.kt
- /home/leanid/Documents/code/java/jseccomp/.agents/CODE_QUALITY.md:5-6

## Important details
- Fail-closed principle: validation mustnever silently accept invalidmetadata; newentriesonlyexpandallowlists,neverbypassthem
- Backwardcompatibility: existing validcomponents and modules mustcontinueto passafterchanges
- settings.gradle.kts currentlydeclares `:tier-e-proto`butthe module directorymay not exist inallclones; thisis a knownCIissue (see issue-20260826-122817)
- The`VALID_GRADLE_MODULES` allowlist duplicationwithsettings.gradle.ktsis intentionalforofflinevalidation butrequiresmanualsyncperbacklog itemu00a73
- IssueTemplateGenerator hasindependentduplicateallowlists that mustbeupdated in lockstepwith BacklogValidatortopreventinconsistentvalidation
- Bothfiles useprivatevalssothereisno shared constant; dualmaintenance is intentionalbutrequires coordinatedchanges
- Allcallers of BacklogValidator.validateBacklogandIssueTemplateGenerator arecontainedwithin :tools:orchestrator perASTscan
- Resolved issue-20260826-122817 explicitlycallstheduplication'whatcurrently makes checkBacklog reject :tier-e-proto' andstatesideally'derive it fromsettings or add aCI drift check'
- MazeWall already usessharedconstants pattern(ProfilerConstants.kt)for criticalvalues,demonstratingarchitecturalprecedent
- Currentduplicationviolates DRY principle andhasalreadycaused real buildfailures pertheresolved backlog item
- SOLID principles andcode quality standards favorcompositionandsingle responsibility,supportingextractionto shared constants
- Thechangepreservesfail-closed behavior:constantsare still compile-time fixedsets, justcentralized

## Side effects
- BacklogValidator validationbehaviorchangesfor issueswith tier-e componentortier-e-proto targetmodules
- BacklogValidatorTest gainsnew test cases coveringTier-E metadata validation
- IssueTemplateGenerator validationbehaviorchanges for tier-ecomponent and tier-e-proto moduleinputs
- AnytestorcodepathusingIssueTemplateGenerator withtier-e metadatawill seedifferent validationoutcomes
- BacklogValidator and IssueTemplateGenerator allowlists mustbekept insync to avoidinconsistent validation results
- BacklogValidator and IssueTemplateGenerator nowshare thesame sourceof truth for validationconstants
- Any futurechangeto componentor module allowlists requires updating onlyBacklogConstants.kt
- NewBacklogConstants.kt filebecomesa criticaldependency for both validationandtemplategeneration
- CI drift check willcatch mismatches between settings.gradle.kts and BacklogConstants early

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260906-011942  file: issue-20260906-011942-align-orchestrator-backlog-validation-with-active-tier-e-met.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
