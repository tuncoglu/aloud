#!/usr/bin/env bash
# Read-only: list release tracks and their releases for a package, via a throwaway edit.
# usage: play-status.sh PACKAGE
set -euo pipefail
# shellcheck source=play-common.sh
. "$(dirname "$0")/play-common.sh"
pkg="${1:?usage: play-status.sh PACKAGE}"; api=$(play_api "$pkg")

id=$(curl -sS -X POST "${auth[@]}" -H 'Content-Length: 0' "$api/edits" | jq -r '.id // empty')
[ -n "$id" ] || { echo "could not open edit (no Play access?)"; exit 1; }
trap 'curl -sS -o /dev/null -X DELETE "${auth[@]}" "$api/edits/$id"' EXIT

curl -sS "${auth[@]}" "$api/edits/$id/tracks" \
  | jq '.tracks // [] | map({track, releases: (.releases // [] | map({name, status, versionCodes, userFraction}))})'
