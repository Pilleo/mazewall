# Tier E bounded teardown and recoverable evidence (2026-09-25)

This is source-dirty, short developer verification of the four lifecycle
review findings, not a long-workload performance or adoption decision.
`REJECT_DEFAULT_PROMOTION` stands; hybrid remains opt-in experimental and
`policy_eligibility=false`. Historical archives and their checksum ledgers
were not modified.

The Kotlin owner stops each exact target/collector name, verifies it is no
longer running, exports its complete log, and only then removes it. When export
fails, the stopped container and its shared evidence directory are retained
with a recovery identifier; another owned container is cleaned independently.
Under the JUnit fixture, shell `run -d` creation holds an opt-in per-run flock
until the request completes. Teardown kills and waits for the controller and
the in-flight creation command, verifies the creation lock is available,
then reconciles names. If settlement cannot be established in time, cleanup
reports an unresolved run rather than claiming success. Sentinel cleanup is
within the same interruption-safe teardown; the interrupt flag is restored
only after every cleanup attempt. Existing direct-shell callers retain their
original behavior.

`rootful-docker-junit.xml` records **8/8** real Docker tests at
`unix:///var/run/docker.sock` from the successful full build. They include a
real delayed Docker creation released after controller cancellation, an
actual failed log export leaving a stopped and readable container, a
SIGKILL controller with exact container cleanup, and interrupted teardown
that removes an unrelated sentinel. The failure-export fixture explicitly
retires its recoverable container after asserting the failure behavior.
`absent-podman-junit.xml` records **8/8 skips** when `podman` was absent, with
the endpoint reason reported. Rootful Podman runtime parity remains unverified.

Focused Kotlin container tests passed, including a small-heap child
(`-Xmx24m`) retaining a 64 MiB log and returning a bounded 4,096-character
summary without reading the entire log into memory. `bash -n
scripts/run_tier_e_split_privilege_fixture.sh` and
`./scripts/adkw check-backlog` passed. The final explicit-rootful
`./gradlew build` passed (264 tasks, 5m34s). Its first attempt failed the
repository's singleton-provider architecture rule because a new method
named `settle` was misidentified as a setter; renaming the helper resolved
the violation and the full rerun passed. These receipts do not qualify a
75,000-operation matrix or default promotion.

`SHA256SUMS` covers these three retained files, excluding itself.
