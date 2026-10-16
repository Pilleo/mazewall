# MazeWall thin normal-JAR starter

This optional starter depends on the normal integration JAR and Boot 4.1.1's basic
starter. It has no executable Boot plugin and does not force Security, MVC, WebFlux
or JPA dependencies. It does not construct a ManagedRuntime, choose a policy/baseline,
bind an operation or install restrictions on application/servlet threads.

When Spring Security is separately present **and**
`mazewall.security.delegate-context=true`, conditional configuration supplies a
`SpringSecurityContextBridge` unless the application already supplied an
`InvocationContextBridge`. Presence alone changes nothing. Explicitly pass that bean
to the six-argument typed `DomainLifecycle` constructor to opt in. Without Security
the conditional class is not linked and the normal five-argument lifecycle changes
no authentication/context defaults.
The supported bridge requires the stable standard thread-local holder strategy;
global/inheritable/custom/replaced strategies reject instead of leaking worker context.

For narrow supported synchronous `@PreAuthorize`/`@PostAuthorize`, explicitly install
`SpringSecurityMethodAdvice.authorizationAdvisor()` in the existing inside-transaction
authorization slot. It uses upstream before/after authorization managers. Effective
rules that suppress their actual denial exceptions reject at binding/recheck. Filtering,
alternative annotation families and denial-return handlers are unsupported and reject.
Post-authorized methods require REQUIRED/REQUIRES_NEW/NESTED/MANDATORY physical
transaction semantics; SUPPORTS/NOT_SUPPORTED/NEVER post-authorization rejects.
No URL authentication configuration or global SecurityContextHolder strategy is changed.

Application-owned trusted platform bootstrap, actual ordinary-JAR resource resolution,
external trusted document/JAR/generation review and explicit required binding remain
mandatory. Context delegation cannot widen kernel policy. Host conditional/method-security
tests alone do not establish executable Boot launch support. The separate
[finished-Boot fixture](../../../demos/spring-boot-sandbox/README.md) uses genuine
bounded deployment/resource resolution and exact published artifacts.

Root module inclusion is declared under `includeSpringIntegration=true`. Normal
library publications and the source-bound optional CI/release lane are wired in
the build; no inclusion patch is required.
See [the complete narrow S3 contract](../mazewall-spring-boot/S3-contract.md) for evidence
and its original phase boundaries, supplemented by the current consumer guides.

## Optional read-only Actuator diagnostics

The normal starter does not force Actuator or a JSON implementation. Add the
Boot-managed `spring-boot-starter-actuator` to the **application** when wanted,
then opt in explicitly:

```properties
mazewall.diagnostics.enabled=true
management.endpoints.web.exposure.include=health,mazewall
```

Register the already explicitly configured `DomainLifecycle` (or a Koog
`PortalToolAdapter`) as a singleton source. The `mazewall` read endpoint reports
source/operation identity, actual generation, lifecycle, admission/rejection and
cancellation counters, delivery/end counters, and retirement state. It has no write,
cancel, policy, start or stop operations. Exposure/authentication remain application
configuration; the property does not install a baseline or authorize an operation.

`mazewallHealthIndicator` is UP only when every observed source is genuinely
prepared-ready with an available generation and live worker. Empty/unavailable,
preparing/draining/closed sources are OUT_OF_SERVICE; failed sources are DOWN.
Delivery counters cannot make an incompletely retired source healthy. An explicit
application bean with that indicator name takes precedence.
This is execution-owner readiness, not approval/binding of every application
method. Applications still validate their explicit required bindings before
admitting traffic; a prepared worker pool cannot authorize an unbound proxy.

Probes inspect existing singleton instances only and never initialize lazy
sources. Spring's normal SmartLifecycle discovery remains independent. Health and
endpoint APIs are independently conditional, and ordinary consumers continue to
run without either. No payloads, resource paths, environment or exception text are
published. See the [full observation contract](../../../docs/internals/designs/core/execution-diagnostics.md).
