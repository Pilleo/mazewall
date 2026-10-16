#!/usr/bin/env bash
# Sourced by the disposable native runner. No cleanup attempt may discard another owner.
declare -a fresh_records=()

fresh_exists() { [[ -e $1 || -L $1 ]]; }
fresh_identity() { stat -c '%d:%i' -- "$1"; }

fresh_hide() {
    local original=$1 backup=$2 identity
    if fresh_exists "$backup"; then
        printf 'HIDE_CONFLICT original=%s backup=%s (preserved)\n' "$original" "$backup" >&2
        return 70
    fi
    identity=$(fresh_identity "$original") || return $?
    # Record ownership BEFORE the move can take effect, including an interrupted/failed move.
    # One builtin appends the complete tuple before any trap can observe a partial entry.
    fresh_records+=("$original" "$backup" "$identity")
    printf 'HIDE_PLAN original=%q backup=%q identity=%q\n' "$original" "$backup" "$identity" >&2
    if mv -T --no-clobber -- "$original" "$backup"; then
        :
    else
        # Preserve the move failure status even if the filesystem operation already took effect.
        local status=$?
        printf 'HIDE_MOVE_FAILED status=%s original=%s backup=%s\n' "$status" "$original" "$backup" >&2
        return "$status"
    fi
    if fresh_exists "$original" || ! fresh_exists "$backup"; then
        printf 'HIDE_INCOMPLETE original=%s backup=%s (preserved)\n' "$original" "$backup" >&2
        return 70
    fi
}

fresh_restore_all() {
    local failed=0 i original backup expected actual status
    for ((i=${#fresh_records[@]}-3; i>=0; i-=3)); do
        original=${fresh_records[i]}
        backup=${fresh_records[i+1]}
        expected=${fresh_records[i+2]}
        if ! fresh_exists "$backup"; then
            # A hide never effected, or an earlier attempt restored this exact original.
            if fresh_exists "$original" && actual=$(fresh_identity "$original") && [[ $actual == "$expected" ]]; then
                continue
            fi
            printf 'RESTORE_MISSING original=%s backup=%s expected=%s (other entries still attempted)\n' "$original" "$backup" "$expected" >&2
            failed=70
            continue
        fi
        if ! actual=$(fresh_identity "$backup") || [[ $actual != "$expected" ]]; then
            printf 'RESTORE_UNOWNED original=%s backup=%s expected=%s (preserved)\n' "$original" "$backup" "$expected" >&2
            failed=70
            continue
        fi
        if fresh_exists "$original"; then
            printf 'RESTORE_CONFLICT original=%s backup=%s (both preserved; no nested move)\n' "$original" "$backup" >&2
            failed=70
            continue
        fi
        # -T never nests a backup inside a directory. No-clobber preserves a raced destination.
        if mv -T --no-clobber -- "$backup" "$original"; then
            :
        else
            status=$?
            printf 'RESTORE_MOVE_FAILED status=%s original=%s backup=%s (preserved)\n' "$status" "$original" "$backup" >&2
            failed=70
            continue
        fi
        if fresh_exists "$backup" || ! fresh_exists "$original" || ! actual=$(fresh_identity "$original") || [[ $actual != "$expected" ]]; then
            printf 'RESTORE_INCOMPLETE original=%s backup=%s (preserved)\n' "$original" "$backup" >&2
            failed=70
        fi
    done
    return "$failed"
}
