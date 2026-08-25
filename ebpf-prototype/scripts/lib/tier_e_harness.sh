#!/usr/bin/env bash
# Tier E harness library — synchronization WITHOUT wall-clock sleeps.
#
# Source this file from _container_inner_*.sh scripts. Conventions:
#   * Every waiter has a bounded timeout and prints a diagnostic on timeout.
#   * Never write `(( expr )) && cmd` at statement level: arithmetic commands
#     return nonzero when the expression is 0, which interacts badly with
#     set -e/-u in surprising ways. Use if-forms.
#   * Never pass open fd numbers to background children: use file-based
#     command channels (see WP-04 cmdfile probe) so EOF semantics stay local.
#
# Requires: SOCK/LOG/PROBE_OUT variables set by caller; client() function.

te_check() { # name expected_regex actual
    if [[ "$3" =~ $2 ]]; then
        PASS=$((PASS+1)); echo "[ok]   $1"
    else
        FAIL=$((FAIL+1)); echo "[FAIL] $1 (wanted /$2/, got '$3')"
    fi
}

te_wait_socket() { # path [timeout_cs]
    local path="$1" i=0 max="${2:-100}"
    until [[ -S "$path" ]]; do
        ((i++)) || true
        if (( i > max )); then return 1; fi
        sleep 0.05
    done
}

te_wait_mapped() { # pid substring [timeout_cs]
    local pid="$1" substr="$2" i=0 max="${3:-150}"
    until grep -q "$substr" "/proc/$pid/maps" 2>/dev/null; do
        ((i++)) || true
        if (( i > max )); then return 1; fi
        sleep 0.02
    done
}

te_wait_file_lines() { # file min_lines [timeout_halfs]
    local file="$1" min="$2" i=0 max="${3:-100}" have
    while :; do
        have=$(wc -l < "$file" 2>/dev/null || echo 0)
        (( have >= min )) && return 0
        ((i++)) || true
        if (( i > max )); then return 1; fi
        sleep 0.05
    done
}

te_wait_idle_status() { # client_fn sock [attempts] -> echoes first non-BUSY reply
    local client_fn="$1" sock="$2" attempts="${3:-60}" r i=0
    while :; do
        r=$($client_fn "$sock" STATUS 2>&1)
        if [[ "$r" != *"ERR BUSY"* ]]; then echo "$r"; return 0; fi
        ((i++)) || true
        if (( i > attempts )); then echo "ERR STILL_BUSY"; return 0; fi
        sleep 0.05
    done
}
