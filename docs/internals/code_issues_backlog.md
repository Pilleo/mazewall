# Code Issues Backlog (Modularized)

The code issues backlog has been modularized and split into individual files to make it highly efficient and token-friendly for developers and AI coding agents.

Please refer to the new backlog directory and index:

👉 **[Backlog Index Directory (docs/internals/backlog/README.md)](backlog/README.md)**

---

### Why this change was made:
- **Token Efficiency:** A 190KB backlog file consumes nearly 50k tokens. Loading it into an AI context for minor tasks is highly wasteful and causes "lost in the middle" recall degradation.
- **Selective Scoping:** Developers and agents can now load specific issue files (e.g. `issue-001-...md`) only when working on those target components, reducing noise and context clutter.
