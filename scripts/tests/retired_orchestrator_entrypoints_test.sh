#!/usr/bin/env bash
set -euo pipefail

scripts=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
fixture=$(mktemp -d)
trap 'rm -rf -- "$fixture"' EXIT
mkdir -p "$fixture/repo/scripts" "$fixture/bin"

for tool in kotlin curl gradle nohup; do
    cat > "$fixture/bin/$tool" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "${0##*/}" >> "$CALLED"
exit 98
STUB
    chmod +x "$fixture/bin/$tool"
done
cp "$fixture/bin/gradle" "$fixture/repo/gradlew"
cp "$scripts/run_orchestrator.sh" "$scripts/new_backlog_issue.sh" "$fixture/repo/scripts/"
sed "s|/tmp/paperclip_loop.lock|$fixture/paperclip_loop.lock|g" \
    "$scripts/run_paperclip_loop.sh" > "$fixture/repo/scripts/run_paperclip_loop.sh"

check_retired() {
    local entrypoint=$1
    local guidance=$2
    shift 2
    local status=0
    (
        cd "$fixture/repo"
        CALLED="$fixture/called" PATH="$fixture/bin:$PATH" bash "scripts/$entrypoint" "$@"
    ) > "$fixture/result.log" 2>&1 || status=$?
    if [[ -e "$fixture/called" ]]; then
        cat "$fixture/called" >&2
        return 1
    fi
    if [[ $status -eq 0 ]] || ! grep -Fq -- "$guidance" "$fixture/result.log"; then
        cat "$fixture/result.log" >&2
        return 1
    fi
}

check_retired run_paperclip_loop.sh 'removed' --dry-run
check_retired run_paperclip_loop.sh 'removed'
check_retired run_orchestrator.sh 'removed' --background
check_retired run_orchestrator.sh 'removed'
check_retired new_backlog_issue.sh './scripts/adkw new-issue' --title 'Local fixture'
printf '%s\n' 'Retired entrypoints failed before invoking any tool.'
