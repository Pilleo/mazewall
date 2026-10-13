#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
demo_dir="${root}/demos/vulnerable-web-app"
compose_file="${demo_dir}/compose.yml"
seccomp_profile="${root}/infra/dev/podman-seccomp.json"
cd "${root}"

run_id=${MAZEWALL_RUN_ID:-$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')}
if [[ ! "${run_id}" =~ ^[a-z0-9][a-z0-9-]{0,40}$ ]]; then
    echo "run-id must be a private token without path separators" >&2
    exit 2
fi
project="mazewall-web-${run_id}"
output="${root}/demos/output/${run_id}"
image="mazewall-web-demo:${run_id}"
port_unprotected=${MAZEWALL_PORT_UNPROTECTED:-8082}
port_protected=${MAZEWALL_PORT_PROTECTED:-8081}
port_profile=${MAZEWALL_PORT_PROFILE:-8083}
mode=${MAZEWALL_DEMO_MODE:-standard}
main_status=startup-failure
main_exit=1
interrupted=0
cleanup_failed=0
identity_ok=0
image_id=""
engine=()
engine_name=""
engine_bin=""
compose=()
acquired=()
fixture_args=()

mkdir -p "${output}"

on_signal() {
    interrupted=1
    main_status=interrupt
    main_exit=$1
    exit "$1"
}

run_bounded() {
    "$@" &
    local pid=$!
    local i=0
    while (( i < 300 )); do
        if ! kill -0 "${pid}" 2>/dev/null; then
            wait "${pid}"
            return $?
        fi
        sleep 0.2
        i=$((i + 1))
    done
    kill -TERM "${pid}" 2>/dev/null || true
    sleep 0.2
    kill -KILL "${pid}" 2>/dev/null || true
    wait "${pid}" 2>/dev/null || true
    return 124
}

json_get() {
    python3 -c 'import json,sys; doc=json.load(sys.stdin); doc=doc[0] if isinstance(doc, list) else doc; print(doc[sys.argv[1]])' "$1"
}

probe_absent() {
    local err rc
    err="${output}/probe-err"
    run_bounded "$@" >"${err}" 2>&1
    rc=$?
    if [[ ${rc} -eq 0 ]]; then
        return 1
    fi
    if grep -Eiq 'no such file|cannot connect|daemon running|connection refused' "${err}"; then
        return 2
    fi
    if [[ ${rc} -eq 1 || ${rc} -eq 125 ]] && grep -Eiq 'no such (object|image|container|volume|network)|(image|container|volume|network) not known|(image|container|volume|network)( [^[:space:]]+)? not found' "${err}"; then
        return 0
    fi
    return 2
}

note_absent() {
    local rc
    probe_absent "$@"
    rc=$?
    if [[ ${rc} -ne 0 ]]; then
        cleanup_failed=1
    fi
}

retire_run_tag() {
    local rc
    if [[ ${#engine[@]} -eq 0 ]]; then
        return
    fi
    probe_absent "${engine[@]}" image inspect "${image}"
    rc=$?
    if [[ ${rc} -eq 0 ]]; then
        return
    fi
    if [[ ${rc} -eq 2 ]]; then
        cleanup_failed=1
        return
    fi
    if ! run_bounded "${engine[@]}" image rm "${image}" >>"${output}/diagnostics" 2>&1; then
        cleanup_failed=1
        return
    fi
    note_absent "${engine[@]}" image inspect "${image}"
}

retain_and_retire() {
    local id
    if [[ ${#compose[@]} -eq 0 ]]; then
        retire_run_tag
        return
    fi
    mapfile -t found < <(run_bounded "${compose[@]}" ps --all --quiet 2>/dev/null || true)
    for id in "${found[@]}"; do
        [[ -n "${id}" ]] || continue
        run_bounded "${engine[@]}" inspect "${id}" > "${output}/inspect-${id}.json" 2>>"${output}/diagnostics" || true
    done
    for service in profile unprotected protected; do
        run_bounded "${compose[@]}" logs --no-color "${service}" > "${output}/logs-${service}.txt" 2>>"${output}/diagnostics" || true
    done
    run_bounded "${compose[@]}" stop >/dev/null 2>&1 || true
    if ! run_bounded "${compose[@]}" down -v >>"${output}/diagnostics" 2>&1; then
        cleanup_failed=1
    fi
    for id in "${found[@]}"; do
        [[ -n "${id}" ]] || continue
        note_absent "${engine[@]}" inspect "${id}"
    done
    note_absent "${engine[@]}" volume inspect "${project}_profile-artifacts"
    note_absent "${engine[@]}" network inspect "${project}_default"
    retire_run_tag
}

cleanup() {
    local status=$?
    trap - EXIT INT TERM
    set +e
    mkdir -p "${output}"
    if [[ ${interrupted} -eq 1 ]]; then
        main_status=interrupt
        main_exit=${status}
    elif [[ ${status} -ne 0 ]]; then
        main_exit=${status}
    fi
    phase retirement
    retain_and_retire
    if [[ ${cleanup_failed} -eq 1 && ${main_exit} -eq 0 ]]; then
        main_status=cleanup-error
        main_exit=1
        printf '%s\n' cleanup-error > "${output}/status"
    elif [[ ${cleanup_failed} -eq 1 ]]; then
        printf '%s\n%s\n' "${main_status}" cleanup-error > "${output}/status"
        echo "cleanup-error" >> "${output}/diagnostics"
    else
        printf '%s\n' "${main_status}" > "${output}/status"
        if [[ ${main_status} == success ]]; then
            echo "retired ${project}" >&2
        fi
    fi
    exit "${main_exit}"
}

trap 'on_signal 130' INT
trap 'on_signal 143' TERM
trap cleanup EXIT

ready_deadline=${MAZEWALL_READY_DEADLINE:-120}
stop_grace=${MAZEWALL_PROFILE_STOP_GRACE:-45}
stop_deadline=${MAZEWALL_PROFILE_STOP_DEADLINE:-75}
benign_body='Log processed: 1.0'

phase() {
    printf '%s\t%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1" >> "${output}/phases.tsv"
}

timeout_rc=0
timeout 0.2 sleep 1 || timeout_rc=$?
if [[ ${timeout_rc} -ne 124 ]]; then
    echo "timeout did not fail closed with 124 (got ${timeout_rc})" >&2
    main_exit=1
    exit 1
fi

fail_profile() {
    local rc=$1
    shift
    printf '%s\n' "$*" | tee -a "${output}/profile-failure" >&2
    main_status=startup-failure
    main_exit=${rc}
    exit "${rc}"
}

port_open() {
    timeout 0.2 bash -c "exec 3<>/dev/tcp/127.0.0.1/$1" >/dev/null 2>&1
}

for port in "${port_unprotected}" "${port_protected}" "${port_profile}"; do
    if port_open "${port}"; then
        echo "port ${port} is occupied; not stopping its owner" >&2
        main_status=port-occupied
        main_exit=1
        exit 1
    fi
done

if [[ -n ${MAZEWALL_CONTAINER_ENGINE:-} ]]; then
    engine_name=${MAZEWALL_CONTAINER_ENGINE}
elif command -v podman >/dev/null 2>&1; then
    engine_name=podman
elif command -v docker >/dev/null 2>&1; then
    engine_name=docker
else
    echo "Neither podman nor docker CLI could be found." >&2
    main_exit=1
    exit 1
fi
if ! command -v "${engine_name}" >/dev/null 2>&1; then
    echo "Selected container engine is not executable: ${engine_name}" >&2
    main_exit=1
    exit 1
fi
engine=("${engine_name}")
engine_bin=$(command -v "${engine_name}")
export MAZEWALL_SECCOMP_PROFILE="${seccomp_profile}"
export COMPOSE_PROJECT_NAME="${project}"
export MAZEWALL_IMAGE="${image}"
export MAZEWALL_PORT_UNPROTECTED="${port_unprotected}"
export MAZEWALL_PORT_PROTECTED="${port_protected}"
export MAZEWALL_PORT_PROFILE="${port_profile}"
export MAZEWALL_RUN_ID="${run_id}"
compose=("${engine[@]}" compose -p "${project}" -f "${compose_file}")

if [[ ${engine_name} == docker ]]; then
    security=$(run_bounded "${engine[@]}" info --format '{{json .SecurityOptions}}') || {
        echo "docker info failed" >&2
        exit 1
    }
    if [[ ${security} != *seccomp* ]]; then
        echo "podman annotation is not Docker-enforced Seccomp" >&2
        main_exit=1
        exit 1
    fi
    printf '%s\n' docker-security-opt > "${output}/seccomp-enforcement"
    cat > "${output}/compose.override.yml" <<EOF
services:
  unprotected:
    security_opt: ["seccomp:${seccomp_profile}"]
  protected:
    security_opt: ["seccomp:${seccomp_profile}"]
  profile:
    security_opt: ["seccomp:${seccomp_profile}"]
EOF
    compose+=(-f "${output}/compose.override.yml")
else
    run_bounded "${engine[@]}" info >/dev/null
    printf '%s\n' podman-annotation > "${output}/seccomp-enforcement"
fi

gradlew=("${MAZEWALL_GRADLEW:-${root}/gradlew}")
if [[ ${mode} == isolated ]]; then
    run_bounded "${gradlew[@]}" :demos:vulnerable-web-app:isolatedVulnerableFixtureJar --build-cache --configuration-cache
    jar_rel=build/libs/isolated-vulnerable-fixture.jar
    export MAZEWALL_SPRING_PROFILES=isolated-vulnerable-fixture
    fixture_args=(--fixture isolated --jar "${demo_dir}/${jar_rel}" --fixture-profile isolated-vulnerable-fixture)
else
    run_bounded "${gradlew[@]}" :demos:vulnerable-web-app:bootJar --build-cache --configuration-cache
    jar_rel=build/libs/vulnerable-app.jar
    export MAZEWALL_SPRING_PROFILES=
    fixture_args=()
fi
export APP_JAR="${jar_rel}"
jar_sha=$(sha256sum "${demo_dir}/${jar_rel}" | awk '{print $1}')
export APP_JAR_SHA256="${jar_sha}"

run_bounded "${compose[@]}" build profile unprotected protected
image_json=$(run_bounded "${engine[@]}" image inspect "${image}")
image_id=$(printf '%s' "${image_json}" | json_get Id)
label=$(printf '%s' "${image_json}" | python3 -c 'import json,sys; doc=json.load(sys.stdin); doc=doc[0] if isinstance(doc, list) else doc; print(doc["Config"]["Labels"]["io.mazewall.demo.jar-sha256"])')
if [[ ${label} != "${jar_sha}" || -z ${image_id} ]]; then
    echo "built image id or JAR digest does not match the selected artifact" >&2
    main_status=identity-failure
    main_exit=4
    exit 4
fi
identity_ok=1

run_bounded "${compose[@]}" up -d --no-build profile unprotected

read_one_id() {
    local service=$1
    local -a ids=()
    mapfile -t ids < <(run_bounded "${compose[@]}" ps --all --quiet "${service}")
    if [[ ${#ids[@]} -ne 1 || -z ${ids[0]} ]]; then
        echo "expected exactly one id for ${service}" >&2
        main_status=startup-failure
        main_exit=1
        exit 1
    fi
    one_id=${ids[0]}
}

read_one_id profile
profile_id=${one_id}
read_one_id unprotected
unprot_id=${one_id}
acquired=("${profile_id}" "${unprot_id}")

require_image() {
    local id=$1 got
    run_bounded "${engine[@]}" inspect "${id}" > "${output}/inspect-${id}.json"
    got=$(json_get Image < "${output}/inspect-${id}.json")
    if [[ ${got} != "${image_id}" ]]; then
        echo "service ${id} image does not match built image ${image_id}" >&2
        main_status=identity-failure
        main_exit=4
        exit 4
    fi
}

require_image "${profile_id}"
require_image "${unprot_id}"

start_protected() {
    [[ ${identity_ok} -eq 1 ]] || {
        main_status=identity-failure
        main_exit=4
        exit 4
    }
    run_bounded "${compose[@]}" up -d --no-build protected
    read_one_id protected
    prot_id=${one_id}
    acquired+=("${prot_id}")
    require_image "${prot_id}"
    python3 - "${output}/inspect-${prot_id}.json" <<'PY' || {
import json, sys
doc = json.load(open(sys.argv[1]))
doc = doc[0] if isinstance(doc, list) else doc
mounts = doc.get("Mounts") or []
ok = any(item.get("Destination") == "/app/profiles" and item.get("Mode") == "ro" for item in mounts)
raise SystemExit(0 if ok else 1)
PY
        echo "protected profile mount is not read-only" >&2
        main_status=identity-failure
        main_exit=4
        exit 4
    }
}

await_ready() {
    local service=$1 id=$2 port=$3 rc=0
    set +e
    timeout "${ready_deadline}" bash -s -- "${service}" "${id}" "${port}" "${engine_name}" "${image_id}" <<'EOF'
set -euo pipefail
service=$1
id=$2
port=$3
engine=$4
image_id=$5
benign='Log processed: 1.0'
while true; do
    if ! "${engine}" inspect "${id}" | python3 -c 'import json,sys; doc=json.load(sys.stdin); doc=doc[0] if isinstance(doc, list) else doc; state=doc.get("State") or {}; raise SystemExit(0 if state.get("Status")=="running" and doc.get("Image")==sys.argv[1] else 1)' "${image_id}"; then
        sleep 0.2
        continue
    fi
    set +e
    out=$(timeout 5 "${engine}" exec "${id}" curl --silent --show-error --connect-timeout 3 --max-time 5 --write-out '\n%{http_code}' "http://127.0.0.1:${port}/admin/log")
    attempt=$?
    set -e
    if [[ ${attempt} -ne 0 ]]; then
        sleep 0.2
        continue
    fi
    parsed=$(printf '%s' "${out}" | python3 -c 'import sys; lines=sys.stdin.read().strip().splitlines(); print(lines[-1] if lines else ""); print("\n".join(lines[:-1]))')
    code=${parsed%%$'\n'*}
    body=${parsed#*$'\n'}
    if [[ ${code} == 404 ]]; then
        echo "HTTP 404 from ${service} is not ready" >&2
        exit 44
    fi
    if [[ ${code} == 500 ]]; then
        echo "HTTP 500 from ${service} is not ready" >&2
        exit 45
    fi
    if [[ ${code} == 200 && ${body} == "${benign}" ]]; then
        exit 0
    fi
    sleep 0.2
done
EOF
    rc=$?
    set -e
    if [[ ${rc} -eq 44 ]]; then
        fail_profile 1 "HTTP 404 from ${service} is not ready"
    fi
    if [[ ${rc} -eq 45 ]]; then
        fail_profile 1 "HTTP 500 from ${service} is not ready"
    fi
    if [[ ${rc} -eq 124 ]]; then
        fail_profile 124 "readiness deadline exceeded for ${service}"
    fi
    if [[ ${rc} -ne 0 ]]; then
        fail_profile 1 "readiness failed for ${service} (exit ${rc})"
    fi
    phase "${service}-ready"
}

exercise_profile() {
    phase profile-workload
    python3 -c 'import sys, zipfile; z=zipfile.ZipFile(sys.argv[1], "w"); z.writestr("benign.txt", "bound upload"); z.close()' "${output}/benign.zip"
    timeout 5 "${engine[@]}" exec "${profile_id}" curl --silent --show-error --connect-timeout 3 --max-time 5 -H 'X-Api-Version: 1.0' "http://127.0.0.1:8083/admin/log" >/dev/null
    timeout 5 "${engine[@]}" exec -i "${profile_id}" curl --silent --show-error --connect-timeout 3 --max-time 5 -F 'file=@-;filename=benign.zip;type=application/zip' 'http://127.0.0.1:8083/upload' < "${output}/benign.zip" >/dev/null
    timeout 5 "${engine[@]}" exec "${profile_id}" curl --silent --show-error --connect-timeout 3 --max-time 5 --get --data-urlencode 'url=http://unprotected:8080/admin/log' 'http://127.0.0.1:8083/proxy' >/dev/null
}

stop_profile() {
    local rc=0
    set +e
    timeout "${stop_deadline}" "${compose[@]}" stop -t "${stop_grace}" profile
    rc=$?
    set -e
    if [[ ${rc} -eq 124 ]]; then
        fail_profile 124 "profile stop exceeded ${stop_deadline}s"
    fi
    if [[ ${rc} -ne 0 ]]; then
        fail_profile 1 "profile stop failed (${rc})"
    fi
    set +e
    reason=$(run_bounded "${engine[@]}" inspect "${profile_id}" | python3 -c 'import json,sys; doc=json.load(sys.stdin); doc=doc[0] if isinstance(doc, list) else doc; state=doc.get("State") or {}; code=state.get("ExitCode"); oom=state.get("OOMKilled"); status=state.get("Status"); error=state.get("Error") or "";
print("profile OOM killed the JVM") if oom is True else print("profile exit 137 is not orderly SIGTERM retirement") if code == 137 else print("profile forced shutdown") if "forced" in error.lower() else print("") if status == "exited" and code in (0, 143) else print("profile retirement was not orderly SIGTERM status=%s exit=%s" % (status, code))')
    rc=$?
    set -e
    if [[ ${rc} -eq 124 ]]; then
        fail_profile 124 "profile inspect after stop timed out"
    fi
    if [[ -n ${reason} ]]; then
        fail_profile 1 "${reason}"
    fi
    phase profile-stopped
}

copy_profile() {
    local copy_rc=0 extract_rc=0
    mkdir -p "${output}/profile"
    set +e
    timeout 60 "${engine[@]}" run --rm \
        --mount "type=volume,source=${project}_profile-artifacts,target=/app/profiles,readonly" \
        --entrypoint tar \
        "${image}" -c -C /app/profiles . \
        | tar -x -C "${output}/profile"
    local statuses=("${PIPESTATUS[@]}")
    copy_rc=${statuses[0]}
    extract_rc=${statuses[1]}
    set -e
    if [[ ${copy_rc} -eq 124 || ${extract_rc} -eq 124 ]]; then
        fail_profile 124 "profile copy timed out"
    fi
    if [[ ${copy_rc} -ne 0 || ${extract_rc} -ne 0 ]]; then
        fail_profile 1 "profile copy failed (${copy_rc}/${extract_rc})"
    fi
}

validate_generation() {
    local rc=0
    set +e
    validated=$(python3 - "${output}/profile" 2>"${output}/profile-failure" <<'PY'
import hashlib, json, re, sys
from pathlib import Path
root = Path(sys.argv[1])
pointer_path = root / "sbob.json"
if not pointer_path.is_file():
    sys.exit("missing profile pointer")
pointer = pointer_path.read_text(encoding="utf-8").strip()
match = re.fullmatch(r'\{"format":"mazewall-profile-generation-v1","generation":"([0-9a-f-]{36})"\}', pointer)
if not match or ".." in match.group(1):
    sys.exit("invalid profile pointer")
generation = match.group(1)
base = (root / ".sbob.json.generations").resolve()
directory = (base / generation).resolve()
if base not in directory.parents:
    sys.exit("profile generation escapes its directory")
needed = ("sbob.json", "stacktraces.json", "integrity.json")
paths = {name: directory / name for name in needed}
missing = [name for name, path in paths.items() if not path.is_file()]
if missing:
    sys.exit("partial generation missing " + ", ".join(missing))
integrity = json.loads(paths["integrity.json"].read_text(encoding="utf-8"))

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

for field, name in (("evidenceSha256", "sbob.json"), ("stackDictionarySha256", "stacktraces.json")):
    expected = integrity.get(field)
    actual = digest(paths[name])
    if not isinstance(expected, str) or expected != actual:
        sys.exit("corrupt " + field + " digest")
if integrity.get("complete") is not True:
    sys.exit("incomplete profile generation")
collectors = integrity.get("collectors")
if not isinstance(collectors, list) or not collectors:
    sys.exit("incomplete collector coverage is absent")
for index, collector in enumerate(collectors):
    if not isinstance(collector, dict) or collector.get("complete") is not True or collector.get("drainComplete") is not True:
        sys.exit("collector[%s] is incomplete" % index)
    dropped = collector.get("droppedEvents")
    if type(dropped) is not int or dropped != 0:
        sys.exit("collector[%s] dropped events" % index)
evidence = paths["sbob.json"].read_text(encoding="utf-8").strip()
if evidence in ("", "{}", "[]"):
    sys.exit("profile evidence is empty")
print(generation)
print("sha256=" + integrity["evidenceSha256"])
print("sha256=" + integrity["stackDictionarySha256"])
PY
    )
    rc=$?
    set -e
    if [[ ${rc} -ne 0 ]]; then
        cat "${output}/profile-failure" >&2
        main_status=startup-failure
        main_exit=1
        exit 1
    fi
    printf '%s\n' "${validated}" > "${output}/profile-generation"
    phase generation-validated
}

phase built
await_ready profile "${profile_id}" 8083
await_ready unprotected "${unprot_id}" 8080
exercise_profile
stop_profile
copy_profile
validate_generation
start_protected
await_ready protected "${prot_id}" 8081

ssrf_target="http://unprotected:8080/admin/log"
phase scenarios
set +e
python3 exploits/run_all.py "http://127.0.0.1:${port_unprotected}" \
    --engine "${engine_bin}" \
    --container "${unprot_id}" \
    --side unprotected \
    --ssrf-target "${ssrf_target}" \
    --run-id "${run_id}" \
    "${fixture_args[@]}" \
    > "${output}/unprotected_results.json"
side_exit=$?
if [[ ${side_exit} -eq 0 ]]; then
    python3 exploits/run_all.py "http://127.0.0.1:${port_protected}" \
        --engine "${engine_bin}" \
        --container "${prot_id}" \
        --side protected \
        --ssrf-target "${ssrf_target}" \
        --run-id "${run_id}" \
        "${fixture_args[@]}" \
        > "${output}/protected_results.json"
    side_exit=$?
fi
if [[ ${side_exit} -eq 0 ]]; then
    python3 exploits/verify_results.py \
        "${output}/unprotected_results.json" \
        "${output}/protected_results.json" \
        "${output}/report.md"
    side_exit=$?
fi
set -e
if [[ ${side_exit} -ne 0 ]]; then
    main_status=scenario-failure
    main_exit=${side_exit}
    exit "${side_exit}"
fi
main_status=success
main_exit=0
exit 0
