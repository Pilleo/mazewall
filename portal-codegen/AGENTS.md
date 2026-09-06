# Portal code generation guidance

- Preserve the boundary between generator inputs and generated dispatcher/stub output.
- Do not hand-edit generated output; change its source schema or generator and regenerate it through the supported build task.
- Keep generated protocol shapes compatible with the broker/worker capability boundary in `docs/internals/designs/enforcer/process-portal-design.md`.

Verification: `./gradlew :portal-codegen:test`
