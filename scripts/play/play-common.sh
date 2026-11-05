# Sourced by the play-*.sh scripts. They authenticate with a short-lived OAuth access token in
# PLAY_ACCESS_TOKEN, which the Play release workflow gets from Google through Workload Identity
# Federation (.github/workflows/play-release.yml). No service-account key file exists anywhere.
: "${PLAY_ACCESS_TOKEN:?PLAY_ACCESS_TOKEN is not set (these scripts run in the Play release workflow)}"
# shellcheck disable=SC2034  # used by the scripts that source this file
auth=(-H "Authorization: Bearer $PLAY_ACCESS_TOKEN")
play_api() { echo "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/$1"; }
play_upload_api() { echo "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/$1"; }
