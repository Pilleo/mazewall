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
}
start_protected

ssrf_target="http://unprotected:8080/admin/log"
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
