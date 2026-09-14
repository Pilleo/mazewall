plugins {
    base
}

/**
 * Non-published aggregate for the broker runtime, code generator, and isolated
 * worker. Child project dependencies retain the broker/worker classpath boundary.
 */
tasks.register("test") {
    group = "verification"
    description = "Runs host tests for every portal-family module"
    dependsOn(":portals:runtime:test", ":portals:codegen:test", ":portals:worker:test")
}

tasks.register("integrationTest") {
    group = "verification"
    description = "Runs process-portal integration tests"
    dependsOn(":portals:runtime:integrationTest")
}

tasks.named("check") {
    group = "verification"
    description = "Runs all portal-family verification tasks"
    dependsOn(":portals:runtime:check", ":portals:codegen:check", ":portals:worker:check", ":portals:integrationTest")
}
