# Vulnerable web application demo

The supported workflow is:

```bash
./scripts/run_vulnerable_app_demo.sh
```

That command is a gated profile-first run of the standard application. It is not an 11-CVE sweep and it does not certify a kernel guarantee. A green exit is claimed only when every declared row below matched its expectation and owned retirement was verified. No such live container run is claimed here.

The [startup and evidence lifecycle diagram](../../docs/diagrams/vulnerable_web_lifecycle.svg) shows the single protection decision and shutdown ordering.

## Standard matrix

| ID | Operation | Unprotected | Protected |
|---|---|---|---|
| `01_admin_control` | `GET /admin/log` with a benign `X-Api-Version` | Completes | Completes |
| `02_upload_control` | `POST /upload` of a new benign ZIP entry | Completes | Completes |
| `03_zip_slip` | ZIP entry that escapes into the owned data root | Escape effect | Denial, no escape file, later benign upload still works |
| `04_ssrf` | `GET /proxy` of the acquired `unprotected` service `/admin/log` on the project network | Exact target response | Controlled fetch denied; direct benign admin stays healthy |
| `05_sqli_limit` | `GET /search` that discloses both seeded rows | Application-level disclosure | Same disclosure, labeled a known application-level limit |

`INFERRED_PERMISSION_FAILURE` is a weaker observation than authenticated policy denial. Zip-slip and SSRF may accept that weaker row only when the source table allows it and the unsafe effect is absent with a later benign control. It is not an authenticated denial. HTTP 404, HTTP 500, timeout, a missing route, or an ineffective payload is not kernel protection. SQL disclosure is inside the application and is not an OS-boundary success.

The checked-in `results_report.md` records only isolated-fixture packaging and profile gating. It is not evidence that the standard application was attacked. Do not rewrite it.

## Profile-first order

The script and any manual reproduction use the same order. Protected start is forbidden before both gates pass:

1. Build the selected JAR and a run-specific image. The image label `io.mazewall.demo.jar-sha256` and every service image id must match that JAR. A stale `:latest` image does not skip the build. Mismatch exits `identity-failure` (4) before a protected verdict.
2. Start only `profile` and `unprotected` on a fresh project `mazewall-web-<run-id>` with an empty project volume `mazewall-web-<run-id>_profile-artifacts`.
3. Wait until each acquired container is running that image and its own `curl` of `/admin/log` returns HTTP 200 and exactly `Log processed: 1.0`. Connect timeout is 3s, request max is 5s, and the phase deadline is 120s (`MAZEWALL_READY_DEADLINE`). HTTP 404/500 never counts as ready. A host listener cannot impersonate the container.
4. Exercise only benign admin, benign upload, and the project-network proxy target `http://unprotected:8080/admin/log`. Do not train attack paths.
5. Stop `profile` with a 45s grace inside a 75s deadline. Accept only orderly SIGTERM retirement (exit 0 or 143), not OOM, exit 137, or a forced shutdown.
6. Copy the volume through a read-only mount into the run directory and validate the pointer, all three generation files, both SHA-256 digests, strict `complete`, and nonempty collector drain evidence.
7. Only then start `protected`, recheck image id and the read-only `/app/profiles` mount, and wait for the same benign response.
8. Run the matrix, write the report, then retire only that project, volume, network, and run image tag.

The run directory is `demos/output/<run-id>/`. It holds `phases.tsv`, `logs-profile.txt`, `logs-unprotected.txt`, `logs-protected.txt`, per-container `inspect-*.json`, `seccomp-enforcement`, `profile/`, `profile-generation`, `unprotected_results.json`, `protected_results.json`, `report.md`, and `status`. Gradle build output stays on the script's stdout; keep that log with the directory. The script does not echo the directory path. Verified disappearance prints `retired mazewall-web-<run-id>` to stderr. Anything short of confirmed absence, including a daemon dial, is `cleanup-error` and does not print `retired`. A cleanup failure after an otherwise successful run is nonzero and keeps the earlier failure code when one already exists.

Occupied default ports 8082, 8081, and 8083 fail as `port-occupied` without stopping the listener.

## Optional isolated fixture

Default mode is `standard` and omits gadget routes. Isolated selection is a separate tested path, not the standard matrix:

```bash
MAZEWALL_DEMO_MODE=isolated ./scripts/run_vulnerable_app_demo.sh
```

That builds `demos/vulnerable-web-app/build/libs/isolated-vulnerable-fixture.jar`, sets Spring profile `isolated-vulnerable-fixture`, and passes `--fixture isolated --jar <that jar> --fixture-profile isolated-vulnerable-fixture`. A missing JAR or inactive profile is `UNSUPPORTED_FIXTURE`, not a denial. Host tests cover that gate with a fake engine. It is not a live container proof and not an arbitrary-code boundary.

## Manual commands

Use the script. These commands only show the same order; they do not repeat its identity, deadline, or retirement checks:

```bash
docker compose -p "$project" -f demos/vulnerable-web-app/compose.yml build profile unprotected protected
docker compose -p "$project" -f demos/vulnerable-web-app/compose.yml up -d --no-build profile unprotected
# benign workload, orderly stop, then validate the generation before the next line
docker compose -p "$project" -f demos/vulnerable-web-app/compose.yml stop -t 45 profile
docker compose -p "$project" -f demos/vulnerable-web-app/compose.yml up -d --no-build protected
```

Podman is preferred when its CLI exists. On this host Podman is absent. Docker 29.9.0 and Compose 5.6.0 are installed, but the selected endpoint `/run/user/1000/podman/podman.sock` is missing, so these commands are not a completed live run. Do not point Docker at a different socket from this guide.

## Host JUnit, no Compose

The core presentation tour does not start Compose or this script:

```bash
./gradlew :demos:vulnerable-web-app:presentationTest --console=plain
```

Those tests compare a prepared service policy and a real publication/incomplete-refusal path. They are not a complete profile→enforcement sweep. That genuine JVM case is blocked: collector[1] drain did not complete, and collector[2] CONNECT was observed without a parsed destination. Do not describe it as passing.

## Boundary

Thread-scoped containment can deny a direct worker syscall. It does not isolate shared JVM memory or arbitrary hostile Java that hops onto an unrestricted pool. Permission exceptions are inferred evidence. Incomplete profile generations are refused unless an operator explicitly sets `-Dmazewall.allow-incomplete-profile-enforcement=true`. This workflow does not set that override.

Profiling and enforcement stay mutually exclusive. The image runs as unprivileged `mazewall`. Only `/app/data` and `/app/uploads` are owned by that user. Published host ports stay on `127.0.0.1`.

## How it works

The standard `bootJar` is `vulnerable-app.jar` and cannot load the custom command gadget. Enabled startup installs the process-safe `NO_EXEC_HOTSPOT` baseline before Spring creates workers, then prepares one fixed-policy domain per service. Service delegates submit synchronous `Callable` bodies to that domain. Installation must finish before target entry. Closing does not remove kernel restrictions.

An incomplete collector drain retains its resources and refuses enforcement. A successful worker drain alone never proves complete profiling.
