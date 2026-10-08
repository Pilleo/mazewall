#!/usr/bin/env bash
set -euo pipefail
source_dir=$(cd -- "$(dirname -- "$0")" && pwd)
evidence=$(mktemp -d /tmp/koog-restoration-tests.XXXXXX)
printf 'restoration_test_evidence=%s\n' "$evidence"
for scenario in normal conflict raced-destination backup-replaced move-failure native-and-move-failure missing-parent hide-moved-then-error signal startup-signal; do
    root="$evidence/$scenario"
    here="$root/tests/consumers/koog-portal"
    mkdir -p "$here/build/fresh-probe/lib" "$root/build" "$root/enforcer/build" "$root/bin"
    cp "$source_dir/run-fresh-native.sh" "$here/"
    if [[ -f $source_dir/fresh-output-ledger.sh ]]; then cp "$source_dir/fresh-output-ledger.sh" "$here/"; fi
    printf 'jar-fixture' > "$here/build/fresh-probe/lib/fixture.jar"
    printf 'consumer-original' > "$here/build/original"
    printf 'root-original' > "$root/build/original"
    printf 'enforcer-original' > "$root/enforcer/build/original"
    cat > "$root/bin/mv" <<'MOVE'
#!/usr/bin/env bash
set -euo pipefail
from=${@: -2:1}
to=${@: -1}
if [[ $TEST_CASE == hide-moved-then-error && $from == "$TEST_ROOT/build" ]]; then
    /usr/bin/mv "$@"
    exit 41
fi
if [[ $TEST_CASE == *move-failure && $from == "$TEST_ROOT/tests/consumers/koog-portal/build.task7-hidden."* ]]; then
    exit 35
fi
if [[ $TEST_CASE == raced-destination && $from == "$TEST_ROOT/tests/consumers/koog-portal/build.task7-hidden."* ]]; then
    mkdir "$to"
    printf 'unknown-user-output' > "$to/user-file"
fi
exec /usr/bin/mv "$@"
MOVE
    cat > "$root/bin/fake-java" <<'JAVA'
#!/usr/bin/env bash
set -euo pipefail
case "$TEST_CASE" in
    conflict)
        mkdir "$TEST_ROOT/tests/consumers/koog-portal/build"
        printf 'unknown-user-output' > "$TEST_ROOT/tests/consumers/koog-portal/build/user-file"
        ;;
    native-and-move-failure) exit 47 ;;
    backup-replaced)
        backup=("$TEST_ROOT/tests/consumers/koog-portal"/build.task7-hidden.*)
        /usr/bin/mv -T "${backup[0]}" "$TEST_ROOT/original-consumer-saved"
        mkdir "${backup[0]}"
        printf 'unknown-backup-output' > "${backup[0]}/user-file"
        ;;
    missing-parent)
        /usr/bin/mv -T "$TEST_ROOT/tests/consumers/koog-portal" "$TEST_ROOT/relocated-consumer"
        ;;
    signal)
        trap '' TERM
        printf '%s\n' "$$" > "$TEST_ROOT/java.pid"
        sleep 60 &
        printf '%s\n' "$!" > "$TEST_ROOT/descendant.pid"
        kill -TERM "$PPID"
        wait
        ;;
esac
JAVA
    cat > "$root/bin/setsid" <<'SESSION'
#!/usr/bin/env bash
set -euo pipefail
if [[ $TEST_CASE == startup-signal ]]; then
    printf '%s\n' "$$" > "$TEST_ROOT/startup.pid"
    kill -TERM "$PPID"
    exec sleep 60
fi
exec /usr/bin/setsid "$@"
SESSION
    chmod +x "$root/bin/mv" "$root/bin/fake-java" "$root/bin/setsid"
    status=0
    if [[ $scenario == startup-signal ]]; then
        PATH="$root/bin:$PATH" TEST_ROOT="$root" TEST_CASE="$scenario" \
            timeout --kill-after=2s 3s bash "$here/run-fresh-native.sh" --locked "$root/bin/fake-java" > "$root/stdout.log" 2> "$root/stderr.log" || status=$?
        if [[ $status != 143 ]]; then
            # Clean the owned injected launcher if the regression stranded it before session creation.
            kill -KILL "$(cat "$root/startup.pid")" 2>/dev/null || :
        fi
    else
        PATH="$root/bin:$PATH" TEST_ROOT="$root" TEST_CASE="$scenario" \
            bash "$here/run-fresh-native.sh" --locked "$root/bin/fake-java" > "$root/stdout.log" 2> "$root/stderr.log" || status=$?
    fi
    case "$scenario" in
        normal)
            [[ $status == 0 && $(cat "$here/build/original") == consumer-original ]]
            ;;
        conflict|raced-destination)
            [[ $status == 70 && $(cat "$here/build/user-file") == unknown-user-output ]]
            [[ $(cat "$here"/build.task7-hidden.*/original) == consumer-original ]]
            [[ ! -e "$here/build/original" ]]
            ! compgen -G "$here/build/build.task7-hidden.*" > /dev/null
            ;;
        backup-replaced)
            [[ $status == 70 && ! -e "$here/build" ]]
            [[ $(cat "$here"/build.task7-hidden.*/user-file) == unknown-backup-output ]]
            [[ $(cat "$root/original-consumer-saved/original") == consumer-original ]]
            ;;
        move-failure|native-and-move-failure)
            expected=70
            if [[ $scenario == native-and-move-failure ]]; then expected=47; fi
            [[ $status == "$expected" && ! -e "$here/build" ]]
            [[ $(cat "$here"/build.task7-hidden.*/original) == consumer-original ]]
            ;;
        missing-parent)
            [[ $status == 70 && ! -e $here ]]
            [[ $(cat "$root"/relocated-consumer/build.task7-hidden.*/original) == consumer-original ]]
            ;;
        hide-moved-then-error)
            [[ $status == 41 && $(cat "$here/build/original") == consumer-original ]]
            ;;
        signal)
            [[ $status == 143 && $(cat "$here/build/original") == consumer-original ]]
            for pid_file in java.pid descendant.pid; do
                pid=$(cat "$root/$pid_file")
                if [[ -e /proc/$pid/stat ]]; then
                    # Zombies cannot run protected work; their reaping belongs to their parent/init.
                    [[ $(awk '{print $3}' "/proc/$pid/stat") == Z ]]
                fi
            done
            ;;
        startup-signal)
            [[ $status == 143 && $(cat "$here/build/original") == consumer-original ]]
            pid=$(cat "$root/startup.pid")
            if [[ -e /proc/$pid/stat ]]; then [[ $(awk '{print $3}' "/proc/$pid/stat") == Z ]]; fi
            ;;
    esac
    # Every scenario must attempt independent entries after the first restore fails.
    [[ $(cat "$root/build/original") == root-original ]]
    [[ $(cat "$root/enforcer/build/original") == enforcer-original ]]
    printf 'RESTORATION_CASE_OK %s status=%s\n' "$scenario" "$status"
done
