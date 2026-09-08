#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 || "$1" != "--label" ]]; then
    echo "usage: $0 --label <name>" >&2
    exit 64
fi

label="$2"
output_dir="build/reports/gradle-baseline/${label}"
mkdir -p "$output_dir"
printf '%s\n' 'task,mode,elapsed_seconds,peak_rss_kib,configured_projects,actionable_tasks,cache_hits,cache_reused' >"$output_dir/summary.csv"

for task in help unitCheck build; do
    for run in cold warm-prime warm; do
        extra_args=(--configuration-cache)
        if [[ "$run" == "cold" ]]; then
            extra_args=(--no-configuration-cache)
        fi
        started="$(date +%s)"
        /usr/bin/time -f '%M' -o "$output_dir/${task}-${run}.rss" \
            ./gradlew "$task" --profile --info --console=plain "${extra_args[@]}" \
            >"$output_dir/${task}-${run}.log" 2>&1
        finished="$(date +%s)"
        elapsed=$((finished - started))
        peak_rss="$(cat "$output_dir/${task}-${run}.rss")"
        executed_tasks="$(sed -nE 's/^([0-9]+) actionable tasks?.*/\1/p' "$output_dir/${task}-${run}.log" | tail -1)"
        executed_tasks="${executed_tasks:-0}"
        configured_projects="$(rg -c '^Evaluating project ' "$output_dir/${task}-${run}.log" || true)"
        cache_hits="$(rg -c ' FROM-CACHE$' "$output_dir/${task}-${run}.log" || true)"
        cache_reused="no"
        if rg -q "Configuration cache entry reused" "$output_dir/${task}-${run}.log"; then
            cache_reused="yes"
        fi
        printf '%s,%s,%s,%s,%s,%s,%s,%s\n' \
            "$task" "$run" "$elapsed" "$peak_rss" "$configured_projects" "$executed_tasks" "$cache_hits" "$cache_reused" \
            >>"$output_dir/summary.csv"
    done
done

echo "Wrote $output_dir/summary.csv"
