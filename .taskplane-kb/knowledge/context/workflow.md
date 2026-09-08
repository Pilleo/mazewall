# Workflow conventions

How this team ships. The loop reads these as defaults.

- **Definition of Done extras (beyond tests + scope diff):** Preserve security invariants, run focused host tests while iterating, run kernel verification for kernel-behavior changes, run the Gradle build merge gate, and keep behavior/threat-model documentation accurate.
- **Branching / merge conventions:** Do not push to the default branch. Work through a scoped backlog issue and the orchestrator-managed review path; stage only files belonging to the active change.
- **Human gates (default: plan approval + EM sign-off):** Plan approval and engineering sign-off are required. Live taskplane hook enforcement is unavailable in this already-running Codex session, so this continuation is advisory until a new session loads it.
