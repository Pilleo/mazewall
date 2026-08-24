---
title: "\U0001F4DD [NOTE]: Root `:test` task requires host Docker/Podman, not runnable\
  \ inside dev container"
severity: MEDIUM
status: open
priority: medium
dependencies: []
target_files:
- build.gradle.kts
target_modules:
- :enforcer
component: testing
effort: medium
paperclip_issue_id: 6b0e0e41-1615-4e5c-aafb-e4b0ec57071b
---

# 📝 [NOTE]: Root `:test` task requires host Docker/Podman, not runnable inside dev container

**Context:** The root `:test` task (`ContainerizedTestRunner`) spawns a Testcontainer using Docker/Podman, which must be available on the host. Running `./gradlew build` from inside the dev container fails because `docker.sock`/`podman.sock` is not mounted inside. The correct inner-container verification commands are: `./gradlew :enforcer:integrationTest :profiler:integrationTest`. The full `./gradlew build` must be run from the host to trigger `ContainerizedTestRunner`.
