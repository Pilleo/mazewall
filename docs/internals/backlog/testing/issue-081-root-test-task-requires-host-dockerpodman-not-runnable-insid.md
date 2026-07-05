---
title: "📝 [NOTE]: Root `:test` task requires host Docker/Podman, not runnable inside dev container"
severity: "MEDIUM"
status: "open"
priority: 4
dependencies: []
component: "testing"
effort: "medium"
---

# 📝 [NOTE]: Root `:test` task requires host Docker/Podman, not runnable inside dev container

**Context:** The root `:test` task (`ContainerizedTestRunner`) spawns a Testcontainer using Docker/Podman, which must be available on the host. Running `./gradlew build` from inside the dev container fails because `docker.sock`/`podman.sock` is not mounted inside. The correct inner-container verification commands are: `./gradlew :enforcer:integrationTest :profiler:integrationTest`. The full `./gradlew build` must be run from the host to trigger `ContainerizedTestRunner`.
