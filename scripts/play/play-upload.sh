#!/usr/bin/env bash
# Upload an .aab to one Play track in a single edit and commit it.
# usage: play-upload.sh PACKAGE AAB TRACK STATUS NAME      (STATUS: draft|completed)
set -euo pipefail
# shellcheck source=play-common.sh
. "$(dirname "$0")/play-common.sh"
pkg="${1:?package}"; aab="${2:?aab}"; track="${3:?track}"
status="${4:?status (draft|completed)}"; name="${5:?release name}"
api=$(play_api "$pkg"); up=$(play_upload_api "$pkg")

id=$(curl -sS -X POST "${auth[@]}" -H 'Content-Length: 0' "$api/edits" | jq -r '.id // empty')
[ -n "$id" ] || { echo "could not open edit"; exit 1; }
# Drop the edit unless it was committed, so a failure leaves nothing half-done.
committed=0
trap '[ "$committed" = 1 ] || curl -sS -o /dev/null -X DELETE "${auth[@]}" "$api/edits/$id"' EXIT

bundle=$(curl -sS -X POST "${auth[@]}" -H 'Content-Type: application/octet-stream' \
  --data-binary "@$aab" "$up/edits/$id/bundles?uploadType=media")
code=$(jq -r '.versionCode // empty' <<<"$bundle")
[ -n "$code" ] || { echo "bundle upload FAILED: $(jq -c '.error // .' <<<"$bundle")"; exit 1; }
echo "bundle uploaded: versionCode $code"

body=$(jq -nc --arg track "$track" --arg name "$name" --arg status "$status" --arg code "$code" \
  '{track:$track, releases:[{name:$name, status:$status, versionCodes:[$code]}]}')
resp=$(curl -sS -X PUT "${auth[@]}" -H 'Content-Type: application/json' -d "$body" \
  "$api/edits/$id/tracks/$track")
jq -e '.track' >/dev/null <<<"$resp" || { echo "track update FAILED: $(jq -c '.error // .' <<<"$resp")"; exit 1; }

done_resp=$(curl -sS -X POST "${auth[@]}" -H 'Content-Length: 0' "$api/edits/$id:commit")
jq -e '.id' >/dev/null <<<"$done_resp" || { echo "commit FAILED: $(jq -c '.error // .' <<<"$done_resp")"; exit 1; }
committed=1
echo "committed: $track release '$name' status=$status versionCode=$code"
