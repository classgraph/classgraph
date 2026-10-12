#!/usr/bin/env bash
set -eu
check=.github/check-workflow-pins.sh
printf '%s\n' '- uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7' \
    '  uses: "owner/repo/subaction@24c54180a607b1449ed407dd24f251e4e9147c8d"' \
    '  uses: ./local-action' | bash "$check" -
for action in 'actions/checkout@v7' 'owner/repo@main' 'owner/repo@1234' \
    'owner/repo@zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz' 'owner/repo'; do
    if printf '  uses: %s\n' "$action" | bash "$check" - >/dev/null 2>&1; then
        printf 'Pin check unexpectedly accepted %s\n' "$action" >&2
        exit 1
    fi
done
bash "$check"
