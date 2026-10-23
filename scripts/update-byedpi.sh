#!/usr/bin/env bash
# Replaces the vendored byedpi with the given commit (default: upstream master).
# Usage: scripts/update-byedpi.sh [commit]
set -euo pipefail

ref="${1:-master}"
root="$(cd "$(dirname "$0")/.." && pwd)"
target="$root/app/src/main/cpp/byedpi"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

git clone --quiet https://github.com/hufrea/byedpi.git "$work/byedpi"
git -C "$work/byedpi" checkout --quiet "$ref"
commit="$(git -C "$work/byedpi" rev-parse HEAD)"

find "$target" -maxdepth 1 -type f \( -name '*.c' -o -name '*.h' \) -delete
cp "$work/byedpi"/*.c "$work/byedpi"/*.h "$work/byedpi/LICENSE" "$target/"
rm -f "$target/win_service.c" "$target/win_service.h"
echo "$commit" > "$target/COMMIT"
echo "byedpi $commit; rebuild and run the strategy test before releasing."
