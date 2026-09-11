# Isolated Vulnerable Fixture Verification Record

This record applies only to the deliberately unsafe fixture artifact. The ordinary application
and compose services do not package its gadget or import routes, so this record makes no claim
about attacks executed against those standard artifacts.

## Provenance

- Recorded at (UTC): 2026-09-11
- Artifact: `build/libs/isolated-vulnerable-fixture.jar`
- Required Spring profile: `isolated-vulnerable-fixture`
- Verification command: `./gradlew :demos:vulnerable-web-app:test --tests demo.vulnapp.VulnerableFixtureProfileTest`
- Result: PASS; the unsafe import controller is absent by default and appears only when the
  explicit isolated fixture profile is active.

## Scope

The verification checks packaging and profile gating. It is not an exploitation transcript and
does not authenticate containment of any CVE. Any future attack result must be regenerated from
the isolated artifact, record its exact command and evidence paths, and distinguish an
authenticated kernel denial from application or infrastructure failure.
