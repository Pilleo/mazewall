# Portal worker guidance

The worker is the restricted process-side endpoint of the portal architecture.

- Keep privileged capability creation and brokering outside the worker.
- Only run work in the worker that is intended to be isolated by its process boundary; do not add broker authority or ambient escape paths.
- Read `docs/internals/designs/enforcer/process-portal-design.md` before changing worker startup, FD handoff, or lifecycle behavior.

Verification: `./gradlew :portal-worker:test`
