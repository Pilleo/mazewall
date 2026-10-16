# Optional synchronous Security and normal-JAR starter (S3 slice)

This opt-in slice uses Boot 4.1.1 dependency management and Spring Security 7.1.1.
Security and Boot auto-configuration are **compile-only** in the integration;
their test dependencies stay test-only. The thin normal-JAR starter adds Boot's
basic starter, without forcing Security, MVC, WebFlux or JPA onto consumers.
Core and Portal have no new framework dependencies.

## Explicit context delegation

The normal five-argument typed `DomainLifecycle` and two-argument dispatcher transfer
no authentication/context metadata. To enable supported Security delegation, pass
`new SpringSecurityContextBridge()` as the sixth lifecycle argument, or as the third
dispatcher argument. The public `InvocationContextBridge` is a trusted explicit
per-invocation decorator, not an ownership or authorization capability.

The supported bridge uses upstream `DelegatingSecurityContextCallable`, capturing
the CURRENT caller context for every invocation before the owned-worker hop. It
uses the exact standard thread-local holder strategy captured at configuration;
global/inheritable/custom strategies and strategy replacement reject. It never sets
an authentication default or changes the global holder strategy. Upstream finally
clears/restores the worker after success, authorization/business failure, fatal Error
and cooperative cancellation. Nested same-domain calls retain the supported upstream
context semantics on that same worker. No connections, servlet requests or arbitrary
ThreadLocals are transferred. Authentication cannot widen the fixed kernel policy.

Supported facilities capture a context reference, not a deep immutable authentication
snapshot. Trusted callers must not mutate captured contexts/authentication during an
invocation; configuration/collaborators remain stable. Context metadata is not heap,
tenant or arbitrary-code isolation.

## Narrow effective method-authorization placement

Explicitly supply `SpringSecurityMethodAdvice.authorizationAdvisor()` as the fourth
advisor in the existing reviewed frozen chain: dispatch, JDBC transaction, diagnosis,
authorization. It composes private actual upstream PreAuthorize/PostAuthorize managers;
the original Spring invocation proceeds once, through the retained target and chain.
No raw-target call, security scheduling or post-commit retrofit occurs. Existing extra
or nested framework proxies are still unsupported and reject.

Only standard synchronous `@PreAuthorize`/`@PostAuthorize` with upstream default
expression handling is supported. Filtering, other annotation families and denial-return
handlers reject at binding/recheck before target entry. This includes JSR-250
declarations from both Jakarta and Javax namespaces:
`RolesAllowed`, `DenyAll` and `PermitAll`; unsupported declarations are never silently
ignored, even when their apparent intent is permissive. Effective rollback rules must
roll back actual upstream AccessDeniedException/AuthorizationDeniedException; suppressing
rules reject. Post-authorized methods require REQUIRED/REQUIRES_NEW/NESTED/MANDATORY,
which establish/require a real physical JDBC transaction before target entry. SUPPORTS,
NOT_SUPPORTED and NEVER are rejected for post-authorization rather than promising
autocommit rollback. Ordinary S2 methods retain their all-seven propagation semantics.
No file/network/independently committed JDBC work is retrospectively rolled back.
An authorization denial is not relabeled a confirmed or inferred kernel denial.

## Conditional starter configuration

`mazewall.security.delegate-context=true` AND Security presence enable conditional
creation of a context-bridge bean, unless the application already supplied one.
Presence alone changes nothing; absent Security never links the optional classes.
Explicitly inject/pass that bean to the typed lifecycle. The starter does not construct
a ManagedRuntime, select a policy/review, bind a required scope or install a baseline.
Application-owned platform bootstrap, genuine typed resources, external trusted review
and explicit required-binding readiness remain mandatory.

## Evidence and release boundaries

- Dedicated **uncontained host** tests: Alice/Bob capture before execution on one worker,
  success/fatal identity/cancel cleanup and clean reuse; actual upstream post-authorization
  inside real same-datasource H2 JDBC rollback; incompatible no-rollback and unsupported
  propagation/features; conditional absence/default/override.
- Published ordinary-JAR **disposable native** Security consumer: actual selected
  implementation Class/JAR/typed owner/external review; Admin/Bob on one owned platform
  worker, actual post-denied SQL rollback, ordinary business error, filesystem denials,
  virtual-caller cooperative cancellation, clean context after every supported delegate
  and actual captured-worker physical retirement. Fatal Error restoration is host-tested;
  core fatal generation retirement remains its separate reviewed contract.
- Normal published starter path independently runs WITHOUT Security at runtime and
  retains actual ordinary-JAR native JDBC/resource/cancel assertions.

These are **ordinary-JAR** proofs. Executable nested Boot and exploded deployment
remain a separate task8/coordinator gate; the reviewed core resolver rejects unsupported
origins. No executable Boot demo/support, combined releaseReady or complete kernel/ACE
claim follows from host auto-configuration or a normal starter JAR. Root inclusion is
coordinator-owned; task9 records the draft settings patch in its report.
