#!/usr/bin/env bash
# One release line per platform in manage-control (MANAGE_CONTROL_API_KEY: a RELEASE key).
#   mc.sh draft <platform>  create a draft; writes MC_ID and MC_VERSION to $GITHUB_ENV
#   mc.sh ship <file>       upload the build, compare hashes, publish
#   mc.sh discard           delete the draft (a failed build leaves nothing behind)
set -euo pipefail
if [ -z "${MANAGE_CONTROL_API_KEY:-}" ]; then
  echo "::error::secret MANAGE_CONTROL_API_KEY is empty: set it with 'gh secret set MANAGE_CONTROL_API_KEY'" >&2
  exit 1
fi
api=https://api.weisscivitech.com/api/v1/service
key=(-H "X-API-Key: $MANAGE_CONTROL_API_KEY")

case "$1" in
  draft)
    subject=$(git log -1 --pretty=%s)
    case "${subject%%:*}" in
      *'!') kind=BREAKING ;;
      feat*) kind=FEATURE ;;
      fix*) kind=FIX ;;
      *) kind=FEATURE ;;  # a merge without a prefix is still a version worth installing
    esac
    notes=$(git log -1 --pretty=%b | grep -v '^Co-Authored-By:' || true)
    draft=$(jq -n --arg kind "$kind" --arg title "$subject" --arg notes "$notes" --arg sha "$GITHUB_SHA" --arg p "$2" \
              '{kind:$kind, title:$title, notes:$notes, commitSha:$sha, platform:$p}' \
            | curl -fsS -X POST "$api/releases" "${key[@]}" -H 'Content-Type: application/json' --data @-)
    echo "MC_ID=$(jq -re .id <<<"$draft")" >> "$GITHUB_ENV"
    echo "MC_VERSION=$(jq -re .version <<<"$draft")" >> "$GITHUB_ENV"
    ;;
  ship)
    name=$(basename "$2")
    stored=$(curl -fsS -X PUT "$api/releases/$MC_ID/artifact?filename=$name" "${key[@]}" \
               -H 'Content-Type: application/octet-stream' --data-binary @"$2" | jq -re .artifact.sha256)
    if [ "$stored" != "$(sha256sum "$2" | cut -d' ' -f1)" ]; then
      echo "hash mismatch after upload" >&2
      exit 1
    fi
    curl -fsS -X POST "$api/releases/$MC_ID/publish" "${key[@]}" > /dev/null
    echo "published $name as $MC_VERSION"
    ;;
  discard)
    [ -n "${MC_ID:-}" ] && curl -fsS -X DELETE "$api/releases/$MC_ID" "${key[@]}" > /dev/null || true
    ;;
esac
