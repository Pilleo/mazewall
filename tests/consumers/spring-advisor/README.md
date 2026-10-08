# Isolated S0 host verification include

This init script includes the optional Spring module only in the assigned root
build, without changing root settings or the compiler. It does not substitute
the optional module, core APIs, or Spring with fixtures. Core is a real project
dependency in this host adapter check; independent **packaged** consumer proof
belongs to the consumer fleet task and subsequent combined release gate.

From the assigned worktree, run under the fleet lock:

```bash
flock /home/leanid/Documents/code/java/jseccomp/.superpowers/sdd/2026-10-06-mazewall-integration-foundation-plan/verification.lock \
  ./gradlew --init-script tests/consumers/spring-advisor/include.init.gradle \
  :integrations:spring:mazewall-spring-boot:test \
  :integrations:spring:mazewall-spring-boot:spotbugsMain \
  :integrations:spring:mazewall-spring-boot:dependencies \
  --configuration testRuntimeClasspath --no-configuration-cache --no-build-cache --console=plain
```

No metadata-skip flags, compiler overrides, Docker, or selected native tests.
Root test finalizers and build-logic verification remain intact. Thread evidence
is in the module test XML's `system-out`. The injectable worker is explicitly
uncontained; this fixture proves Spring placement, not kernel enforcement.
