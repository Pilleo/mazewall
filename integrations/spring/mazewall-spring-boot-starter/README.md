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
tests do not establish executable Boot launch support. Nested Boot/exploded origins still
reject in the reviewed task9 baseline; task8/coordinator owns that release gate.

Root module inclusion is coordinator-owned. Task9 supplies a draft settings patch and
a task-owned fixture init script for focused normal-JAR publication checks.
See [the complete narrow S3 contract](../mazewall-spring-boot/S3-contract.md) for evidence
and exact remaining Boot/exploded/demo/combined-release gates.
