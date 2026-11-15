#!/usr/bin/env bash
set -euo pipefail

# Dedicated rootful-Docker lane. It deliberately does not fall back to the
# ordinary Podman runner or publish an image.
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$root"
[[ $# == 4 && $1 == --image && $3 == --gradle-home ]] || {
    echo 'usage: run_confined_helper_tests.sh --image sha256:IMAGE_ID --gradle-home ABSOLUTE_PATH' >&2
    exit 2
}
image=$2
gradle_home=$4
[[ "$image" =~ ^sha256:[0-9a-f]{64}$ && "$gradle_home" == /* && -d "$gradle_home" && ! -L "$gradle_home" ]] || exit 2
gradle_home=$(cd "$gradle_home" && pwd -P)
[[ "$root" != *','* && "$gradle_home" != *','* && "$root" != *$'\n'* && "$gradle_home" != *$'\n'* && "$root" != *$'\r'* && "$gradle_home" != *$'\r'* ]] || {
    echo 'unsafe bind mount path' >&2; exit 2;
}
uid=$(id -u)
gid=$(id -g)
[[ $uid -ne 0 && -O "$gradle_home" ]] || { echo 'caller-owned non-root Gradle home required' >&2; exit 2; }
candidate="$root/.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b4-d2-implementation-001/matched-control020/environment-preparation003/candidate-gradle-home"
if [[ "$gradle_home" == "$root" || "$gradle_home" == "$root/"* ]]; then
    [[ "$gradle_home" == "$candidate" ]] || { echo 'ambiguous checkout/cache alias' >&2; exit 2; }
fi
profile="$root/infra/dev/confined-helper-seccomp.json"
printf '%s  %s\n' 94b42d4064fc993773f44488ea55406017ab1ef5a0ee5ccc3e5fd19859763136 "$profile" | sha256sum -c -
docker=(docker --host unix:///var/run/docker.sock)
[[ -S /var/run/docker.sock ]] || { echo 'rootful local Docker socket required' >&2; exit 2; }
security=$("${docker[@]}" info --format '{{json .SecurityOptions}}')
python3 -c 'import json,sys; sys.path.insert(0,sys.argv[1]); from verify_confined_helper_results import check_engine; check_engine(json.loads(sys.argv[2]))' "$root/scripts" "$security"
[[ $("${docker[@]}" image inspect --format '{{.Id}}' "$image") == "$image" ]] || exit 2
host_map=$(cat /proc/self/uid_map)
[[ "$host_map" =~ ^[[:space:]]*0[[:space:]]+0[[:space:]]+4294967295[[:space:]]*$ ]] || {
    echo 'initial user namespace is required' >&2; exit 2;
}
before_index=$(sha256sum "$root/.git/index" | cut -d' ' -f1)
before_head=$(git --no-optional-locks -C "$root" rev-parse HEAD)
source_digest() {
    git --no-optional-locks -C "$root" ls-files -co --exclude-standard -z | python3 -c '
import hashlib, os, sys
h=hashlib.sha256()
for path in sorted(set(sys.stdin.buffer.read().split(b"\0")) - {b""}):
    if path == b".adk" or path.startswith(b".adk/") or path == b".taskplane" or path.startswith(b".taskplane/"): continue
    h.update(path+b"\0")
    try:
        s=os.lstat(path); h.update(str(s.st_mode).encode()+b"\0")
        if os.path.islink(path): h.update(os.readlink(path))
        elif os.path.isfile(path):
            with open(path,"rb") as f:
                for block in iter(lambda:f.read(1048576),b""): h.update(block)
    except FileNotFoundError: h.update(b"MISSING")
print(h.hexdigest())'
}
before_source=$(source_digest)
started=$(date +%s%N)
name="mazewall-confined-helper-$$-$started"
receipt="$root/build/reports/confined-helper-$started-$$"
mkdir -p "$root/build/reports"
mkdir "$receipt"
printf '%s\n' "$image" > "$receipt/image-id.txt"
printf '%s\n' 94b42d4064fc993773f44488ea55406017ab1ef5a0ee5ccc3e5fd19859763136 > "$receipt/profile-sha256.txt"
printf '%s\n' "$before_index" > "$receipt/index-before.sha256"
printf '%s\n' "$before_head" > "$receipt/head-before.txt"
printf '%s\n' "$before_source" > "$receipt/source-before.sha256"
printf '%s\n' "$started" > "$receipt/start-ns.txt"
host_userns=$(readlink /proc/self/ns/user)
printf '%s\n' "$host_userns" > "$receipt/host-userns.txt"
id=$("${docker[@]}" create --pull=never --name "$name" --init --userns=host --network bridge \
    --read-only --cap-drop ALL --cap-add CHECKPOINT_RESTORE --cap-add SETUID --cap-add SETGID --cap-add SETPCAP \
    --security-opt no-new-privileges --security-opt "seccomp=$profile" --user 0:0 \
    --pids-limit 1024 --memory 8g --cpus 2 --ulimit core=0:0 --tmpfs /tmp:rw,nosuid,nodev,exec,size=1g \
    --env HOME=/tmp --env JAVA_HOME=/opt/java/openjdk --env GRADLE_USER_HOME=/container-gradle-home \
    --mount "type=bind,src=$root,dst=$root" --mount "type=bind,src=$gradle_home,dst=/container-gradle-home" \
    --workdir "$root" --entrypoint /usr/bin/timeout "$image" --signal=TERM --kill-after=15s 2700s \
    /bin/sh -c '
        set -eu
        command -v setpriv >/dev/null || { echo "setpriv unavailable" >&2; exit 2; }
        map=$(cat /proc/self/uid_map)
        [ "$(printf "%s" "$map" | awk "{print \$1,\$2,\$3}")" = "0 0 4294967295" ] || exit 2
        [ "$(readlink /proc/self/ns/user)" = "$3" ] || exit 2
        exec setpriv --reuid="$1" --regid="$2" --clear-groups --bounding-set=-all,+checkpoint_restore \
            --inh-caps=-all,+checkpoint_restore --ambient-caps=-all,+checkpoint_restore --no-new-privs \
            /bin/sh -c '\''
                set -eu
                [ "$(readlink /proc/self/ns/user)" = "$3" ]
                readlink /proc/self/ns/user > "$4/workload-userns.txt"
                cat /proc/self/uid_map > "$4/workload-uid-map.txt"
                grep -E " /dev(/null)? " /proc/self/mountinfo > "$4/dev-mountinfo.txt"
                stat -c "%F %t:%T %d %n" /dev /dev/null > "$4/dev-stat.txt"
                stat -c "%F %a %n" /proc/self/map_files > "$4/map-files-probe.txt"
                cat /proc/self/status > "$4/workload-status.txt"
                [ "$(id -u)" = "$1" ] && [ "$(id -g)" = "$2" ]
                [ -z "$(awk "/^Groups:/ {\$1=\"\"; print}" /proc/self/status | tr -d "[:space:]")" ]
                grep -qx "$(printf "NoNewPrivs:\\t1")" /proc/self/status
                for field in CapInh CapPrm CapEff CapBnd CapAmb; do
                    grep -qx "$(printf "%s:\\t0000010000000000" "$field")" /proc/self/status
                done
                exec ./gradlew :enforcer:integrationTest --rerun -PmazewallKernelLane=CONFINED --no-daemon --no-parallel --max-workers=1
            '\'' sh "$1" "$2" "$3" "$4"
    ' sh "$uid" "$gid" "$host_userns" "$receipt")
[[ "$id" =~ ^[0-9a-f]{64}$ ]] || exit 2
printf '%s\n' "$id" > "$receipt/container-id.txt"
finish() {
    local status=$?
    trap - EXIT
    set +e
    local state
    state=$(timeout 15s "${docker[@]}" inspect --format '{{json .State}}' "$id") || status=98
    printf '%s\n' "$state" > "$receipt/state-before-cleanup.json"
    if ! python3 -c 'import json,sys; x=json.loads(sys.argv[1]); sys.exit(0 if not x["Running"] and x["Pid"]==0 else 1)' "$state"; then
        status=98
        timeout 25s "${docker[@]}" stop --time 15 "$id" >/dev/null 2>&1
        state=$(timeout 15s "${docker[@]}" inspect --format '{{json .State}}' "$id") || status=98
        python3 -c 'import json,sys; x=json.loads(sys.argv[1]); sys.exit(0 if not x["Running"] and x["Pid"]==0 and not x["OOMKilled"] else 1)' "$state" || status=98
    fi
    printf '%s\n' "$state" > "$receipt/state-final.json"
    sha256sum "$root/.git/index" | cut -d' ' -f1 > "$receipt/index-after.sha256"
    git --no-optional-locks -C "$root" rev-parse HEAD > "$receipt/head-after.txt"
    source_digest > "$receipt/source-after.sha256"
    [[ $(cat "$receipt/index-after.sha256") == "$before_index" && $(cat "$receipt/head-after.txt") == "$before_head" && $(cat "$receipt/source-after.sha256") == "$before_source" ]] || {
        echo 'source or index changed during run' >&2; status=99;
    }
    printf '%s\n' "$status" > "$receipt/final-exit.txt"
    python3 "$root/scripts/verify_confined_helper_results.py" ownership --input "$receipt" --uid "$uid" --recursive || status=98
    printf '%s\n' "$status" > "$receipt/final-exit.txt"
    printf 'Receipt: %s\n' "$receipt"
    exit "$status"
}
trap finish EXIT
mounts=$("${docker[@]}" inspect --format '{{json .Mounts}}' "$id")
printf '%s\n' "$mounts" > "$receipt/mounts.json"
"${docker[@]}" inspect --format '{{json .HostConfig}}' "$id" > "$receipt/host-config.json"
"${docker[@]}" inspect --format '{{json .Config.User}}' "$id" > "$receipt/container-user.json"
"${docker[@]}" inspect --format '{{json .Config.WorkingDir}}' "$id" > "$receipt/container-workdir.json"
"${docker[@]}" inspect --format '{{json .Config.Entrypoint}}' "$id" > "$receipt/container-entrypoint.json"
python3 - "$receipt" "$root" <<'PY'
import json, pathlib, sys
p = pathlib.Path(sys.argv[1])
if (json.loads((p/'container-user.json').read_text()) != '0:0' or
    json.loads((p/'container-workdir.json').read_text()) != sys.argv[2] or
    json.loads((p/'container-entrypoint.json').read_text()) != ['/usr/bin/timeout']):
    raise SystemExit('unexpected container configuration')
PY
python3 "$root/scripts/verify_confined_helper_results.py" mounts --input "$receipt/mounts.json" --root "$root" --gradle-home "$gradle_home"
python3 "$root/scripts/verify_confined_helper_results.py" host --input "$receipt/host-config.json" --policy "$profile"
set +e
python3 - "$receipt" timeout --signal=TERM --kill-after=15s 2730s "${docker[@]}" start --attach "$id" <<'PY'
import pathlib, subprocess, sys
receipt = pathlib.Path(sys.argv[1]); process = subprocess.Popen(sys.argv[2:], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
limit = 16 * 1024 * 1024; used = 0; overflow = False
with (receipt / 'build.log').open('wb') as out:
    while chunk := process.stdout.read(65536):
        remaining = max(0, limit - used)
        out.write(chunk[:remaining]); used += min(len(chunk), remaining)
        overflow |= len(chunk) > remaining
result = process.wait()
(receipt / 'docker-start-exit.txt').write_text(str(result) + '\n')
(receipt / 'log-overflow.txt').write_text(str(overflow).lower() + '\n')
sys.exit(97 if overflow else result)
PY
result=$?
set -e
[[ $result == 0 ]] || exit "$result"
"${docker[@]}" inspect --format '{{json .State}}' "$id" > "$receipt/success-state.json"
python3 "$root/scripts/verify_confined_helper_results.py" state --input "$receipt/success-state.json"
python3 "$root/scripts/verify_confined_helper_results.py" identity --input "$receipt/workload-status.txt" --uid "$uid" --gid "$gid"
python3 "$root/scripts/verify_confined_helper_results.py" namespace --input "$receipt/workload-userns.txt" --host-namespace "$receipt/host-userns.txt" --uid-map "$receipt/workload-uid-map.txt"
foreign=$(find "$root/enforcer/build/test-results/integrationTest" -xdev ! -uid "$uid" -print -quit)
[[ -z "$foreign" ]] || { echo "non-caller-owned test output: $foreign" >&2; exit 1; }
python3 "$root/scripts/verify_confined_helper_results.py" ownership --input "$gradle_home" --uid "$uid"
python3 "$root/scripts/verify_confined_helper_results.py" ownership --input "$root/enforcer/build/test-results/integrationTest" --uid "$uid" --recursive
set +e
python3 "$root/scripts/verify_confined_helper_results.py" confined --results "$root/enforcer/build/test-results/integrationTest" --since-ns "$started" --copy-to "$receipt/xml" > "$receipt/validator.log" 2>&1
validator=$?
set -e
printf '%s\n' "$validator" > "$receipt/validator-exit.txt"
cat "$receipt/validator.log"
[[ $validator == 0 ]] || exit "$validator"
sha256sum "$receipt"/xml/TEST-*.xml > "$receipt/xml.sha256"
