---
title: "Classify remote document limits as invalid Koog input"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "integrations"
target_modules: []
target_files:
  - "demos/koog-document-agent/src/main/kotlin/demo/koog/SafeToolFailures.kt"
target_symbols:
  - "toolFailureKind"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Classify remote document limits as invalid Koog input

**Context:**
Approved inline follow-up Task3. A516-byte129-heading document reaches worker and fails its limit, but completed remote IllegalArgumentException becomes INTERNAL_FAILURE. Map known trusted remote validation type to INVALID_INPUT, retain original/local safe categories, no message parsing or broader unknown-type mapping.

**Needed:**
1. Write host classifier RED and actual generated-Portal invalid-then-valid regression, minimal remote-type mapping, marker/report-count/docs updates; verify11host/3native then final source-bound/full gates.

## Execution

- [x] Host classifier and actual generated-Portal limit regression RED->GREEN.
- [x] Known-type mapping, safe marker/docs/report counts and11host/3native suite.
- [ ] One final independent review and source/full gates.

Task3 complete: the host test observed INVALID_INPUT expected/INTERNAL_FAILURE
actual; the packaged native probe also observed RED after the actual worker rejected
a516-byte129-heading document. Mapping only the known remote IllegalArgumentException
type makes both GREEN. Original completed remote failure/evidence is retained, valid
outline recovery uses the same fixed policy, and unknown/uncertain/access-denied
classifications stay unchanged. No exception-message parsing or worker-limit changes.

Final focused run:11host/3native cases, zero failures/errors/skips, against the
approved local74c1493c963b SDK with strict configuration-cache reuse. Logs:
`/tmp/opencode/review-followup-remote-validation-{red,native-red,final}.log`.

One independent whole-change review found no further classification issue.
Final: Ruling: hostile-worker authenticity, live-provider safety, full I/O deadlines,
production load and remote/human qualification remain explicit external/non-goals.
Cost if wrong: these local typed mappings and fixture observations do not supply
those broader trust, disclosure, performance or release guarantees.

---
<!-- id: issue-20261030-075255-456-classify-remote-document-limits-as-invalid-koog-in  file: issue-20261030-075255-456-classify-remote-document-limits-as-invalid-koog-in.md -->
