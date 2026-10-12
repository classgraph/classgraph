#!/usr/bin/env bash
# Check active workflows and the templates that may be copied into that directory.
set -eu
if [ "$#" -eq 0 ]; then
    set -- .github/workflows/*.yml benchmark/*-workflow.yml
fi
awk '
    /^[[:space:]]*(-[[:space:]]*)?uses[[:space:]]*:/ {
        action = $0
        sub(/^[[:space:]]*(-[[:space:]]*)?uses[[:space:]]*:[[:space:]]*/, "", action)
        sub(/[[:space:]]+#.*/, "", action)
        gsub(/[\047\042]/, "", action)
        sub(/[[:space:]]*$/, "", action)
        if (action ~ /^\.\//) next
        ref = action
        sub(/^.*@/, "", ref)
        if (action ~ /@/ && length(ref) == 40 && ref ~ /^[0-9a-f]+$/) next
        if (action ~ /^docker:\/\/.*@sha256:/ && length(ref) == 71 && ref ~ /^sha256:[0-9a-f]+$/) next
        print FILENAME ":" FNR ": action is not pinned to an immutable revision: " action > "/dev/stderr"
        bad = 1
    }
    END { exit bad }
' "$@"
