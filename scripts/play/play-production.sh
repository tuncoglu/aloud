#!/usr/bin/env bash
# Point a package's production track at an already-uploaded bundle (full rollout) and commit the edit.
# usage: play-production.sh PACKAGE VERSIONCODE VERSIONNAME ["release notes (en-US)"] [TRACK]
# TRACK defaults to production; a Wear OS app may use the form-factor track wear:production instead.
# Without notes the existing release notes are kept. Play usually sends the change for review by itself;
# if it refuses that, the edit is committed unsent and you press "Send for review" in the Console.
# While the app has never been published ("draft app"), Play accepts only draft releases: the release
# is then saved as a draft and the first submission is done in the Console (Publishing overview).
set -euo pipefail
# shellcheck source=play-common.sh
. "$(dirname "$0")/play-common.sh"
pkg="${1:?package}"; code="${2:?versionCode}"; vname="${3:?versionName}"; notes="${4:-}"; track="${5:-production}"
api=$(play_api "$pkg")

eid=""; committed=0
trap '[ "$committed" = 1 ] || [ -z "$eid" ] || curl -sS -o /dev/null -X DELETE "${auth[@]}" "$api/edits/$eid"' EXIT

# release STATUS: open an edit, set the track, commit. On a refused commit it sets commit_err.
release() {
  local status="$1" cur body resp done_resp
  eid=$(curl -sS -X POST "${auth[@]}" -H 'Content-Length: 0' "$api/edits" | jq -r '.id // empty')
  [ -n "$eid" ] || { echo "could not open edit"; return 1; }
  cur=$(curl -sS "${auth[@]}" "$api/edits/$eid/tracks/$track")
  echo "before: $(jq -c '[.releases[]? | {name,status,versionCodes}]' <<<"$cur")"
  body=$(jq -c --arg code "$code" --arg name "$code ($vname)" --arg notes "$notes" --arg track "$track" --arg status "$status" '
    {track:$track, releases:[{
       name:$name, status:$status, versionCodes:[$code],
       releaseNotes: (if $notes != "" then [{language:"en-US", text:$notes}] else ((.releases[0].releaseNotes) // []) end)
    }]}' <<<"$cur")
  resp=$(curl -sS -X PUT "${auth[@]}" -H 'Content-Type: application/json' -d "$body" "$api/edits/$eid/tracks/$track")
  jq -e '.track' >/dev/null <<<"$resp" || { echo "$track update FAILED: $(jq -c '.error // .' <<<"$resp")" ; return 1; }

  done_resp=$(curl -sS -X POST "${auth[@]}" -H 'Content-Length: 0' "$api/edits/$eid:commit")
  if jq -e '.id' >/dev/null <<<"$done_resp"; then
    committed=1; echo "committed: $track -> $code ($vname), status $status"
  elif jq -e '.error.message | test("changesNotSentForReview")' >/dev/null <<<"$done_resp"; then
    done_resp=$(curl -sS -X POST "${auth[@]}" -H 'Content-Length: 0' "$api/edits/$eid:commit?changesNotSentForReview=true")
    jq -e '.id' >/dev/null <<<"$done_resp" || { echo "commit FAILED: $(jq -c '.error // .' <<<"$done_resp")" ; return 1; }
    committed=1; echo "committed UNSENT: $track -> $code ($vname); press 'Send for review' in the Play Console"
  else
    commit_err=$(jq -r '.error.message // "unknown error"' <<<"$done_resp")
    curl -sS -o /dev/null -X DELETE "${auth[@]}" "$api/edits/$eid"; eid=""
  fi
}

commit_err=""
release completed
[ "$committed" = 1 ] && exit 0
if grep -q 'draft app' <<<"$commit_err"; then
  echo "Play: \"$commit_err\" (the app has never been published); saving the release as a draft instead"
  commit_err=""
  release draft
  if [ "$committed" = 1 ]; then
    echo "DRAFT saved: $track -> $code ($vname). Finish in the Play Console: Production → edit the release →"
    echo "Review release → Start rollout, then Publishing overview → Send for review."
    exit 0
  fi
fi
echo "commit FAILED: ${commit_err:-see above}"; exit 1
