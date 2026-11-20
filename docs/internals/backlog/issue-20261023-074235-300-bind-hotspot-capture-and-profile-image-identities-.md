---
title: "Bind HotSpot capture and profile image identities across digest representations"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "docs/superpowers/plans/2026-10-23-hotspot-remaining-acceptance.md"
target_symbols:
  - "hotSpotOwner"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Bind HotSpot capture and profile image identities across digest representations

**Context:**
Task2 review verified that Gadget supplies a bare manifest digest to the companion, while Kube watched metadata/profile Spec.ImageID retain the API image reference. The current HotSpot ownership check and offline profile check compare those strings literally, preventing valid publication/verification. Containerd also separates image-name tags from ImageRef digests; those fields must not be conflated.

**Needed:**
1. Reproduce the shared HotSpot ownership and saved-profile full-reference versus bare-digest mismatch using host fixtures reflecting actual pinned runtime contracts; validate canonical immutable manifest digests only at HotSpot boundaries, retain original metadata/reference/local-ID provenance and reject wrong digests without image resolution or changing baseline API strings/CallIDs.

**Verified source contract (2026-10-23):** N `targets_resolver.go:48` consumes
`Runtime.ContainerImageDigest`; the pinned Gadget CRI adapter strips the repository
prefix from `ImageRef`. The current watched metadata retains Kube status.ImageID
unchanged (`pkg/objectcache/shared_container_data.go:199,208`). Its HotSpot sink
compares that string literally (`hotspot_capture.go:14-15`); the offline Profile
checker also compares literal saved Spec.ImageID with the capture target. This
does not justify altering baseline profile metadata or the shared CallID scope.

Pinned containerd1.7.33 `pkg/cri/server/container_status.go:38-60` separately uses
a repository tag for ImageSpec.Image and a repository digest for ImageRef.
Task2's independent review identified both the target-reference mismatch and
all-fields-equal admission fixture as Important findings; both corrections now
pass focused regressions and independent re-review. Task3's saved-profile
comparison also passed its host gate. Task4b's shared manager ownership correction
passes its independent host review, preserving watched/save strings and legacy
hashes. Final triage corrected the remaining Minor repository-reference grammar
disagreement across acquisition, trust inputs and offline checking using the
already installed strict parser; scoped final re-review marks it addressed.
No live runtime query was needed or executed.

**Execution ownership:** Remaining plan Task2 corrects snapshot admission and
the target digest; Task3 adds the strictly parsed saved-profile comparison;
Task4 fixes the existing shared HotSpot ownership boundary with a host regression.
Keep independent original reference/name/local-ID evidence and exact selected
manifest identity. Wrong digests, mutable references used as identity and local
config IDs substituted for manifest digests must remain rejected. The issue stays
open until source regression/review and actual integration requirements are met.

**Host completion checkpoint:** The final combined runner/verifier/CLI race and
vet checks pass on the reviewed final source. This does not establish actual
manifest-versus-index/config custody, runtime representations or persisted-profile
acceptance. The issue stays open for those external integration gates; no release
pin, image, cluster or native execution was performed.

---
<!-- id: issue-20261023-074235-300-bind-hotspot-capture-and-profile-image-identities-  file: issue-20261023-074235-300-bind-hotspot-capture-and-profile-image-identities-.md -->
