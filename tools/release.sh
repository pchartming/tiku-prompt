#!/usr/bin/env bash
# Standard release helper for Tiku Prompt.
#
# The default action is a read-only readiness check. `build` produces a signed
# APK plus a source archive and checksums under dist/release/<tag>/. The APK is
# built unsigned on the remote builder and signed locally; the release keystore
# must live outside this repository. `publish-github` is opt-in and requires
# explicit authorization; it pushes the tag and creates a GitHub Release.
#
# Usage:
#   tools/release.sh check [--tag vX.Y.Z]
#   tools/release.sh build [--tag vX.Y.Z]
#   tools/release.sh publish-github --authorize-github [--tag vX.Y.Z]
#                         [--execute]
#
# Configuration is read from the environment or a gitignored .env.release file:
#   TIKU_RELEASE_KEYSTORE          release keystore path (outside this repo)
#   TIKU_RELEASE_KEY_ALIAS         key alias
#   TIKU_RELEASE_KEYSTORE_PASSWORD keystore password (never committed)
#   TIKU_RELEASE_KEY_PASSWORD      key password (never committed)
#   TIKU_RELEASE_CERT_SHA256       expected signer certificate SHA-256
#   GH_TOKEN                       token owned by pchartming, for publish-github
#   TIKU_RELEASE_NOTES_FILE        default docs/releases/<tag>.md
#   TIKU_RELEASE_ENV_FILE          default .env.release
#
# Publishing does not force-push, rebuild a release, change repository
# visibility, or publish a debug APK.
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

ENV_FILE="${TIKU_RELEASE_ENV_FILE:-$PROJECT_ROOT/.env.release}"
if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
fi

# Keep credentials local to this shell. Child processes only receive the
# specific variables needed by keytool/apksigner; the GitHub token is written
# to a private curl config file instead of the environment or argv.
RELEASE_KEYSTORE_PASSWORD="${TIKU_RELEASE_KEYSTORE_PASSWORD:-}"
RELEASE_KEY_PASSWORD="${TIKU_RELEASE_KEY_PASSWORD:-}"
RELEASE_GH_TOKEN="${GH_TOKEN:-${GITHUB_TOKEN:-}}"
unset GH_TOKEN GITHUB_TOKEN
export -n TIKU_RELEASE_KEYSTORE_PASSWORD TIKU_RELEASE_KEY_PASSWORD 2>/dev/null || true

if [ -t 1 ]; then
  C_RESET=$'\033[0m'; C_RED=$'\033[0;31m'; C_GREEN=$'\033[0;32m'
  C_YELLOW=$'\033[1;33m'; C_CYAN=$'\033[0;36m'
else
  C_RESET=""; C_RED=""; C_GREEN=""; C_YELLOW=""; C_CYAN=""
fi
info() { printf '%s[release]%s %s\n' "$C_CYAN" "$C_RESET" "$*"; }
ok()   { printf '%s[release] [OK]%s %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn() { printf '%s[release] [!]%s %s\n' "$C_YELLOW" "$C_RESET" "$*" >&2; }
die()  { printf '%s[release] [x]%s %s\n' "$C_RED" "$C_RESET" "$*" >&2; exit 1; }

usage() {
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"
}

ACTION="check"
EXECUTE=0
AUTHORIZE_GITHUB=0
TAG=""
GITHUB_REPO="pchartming/tiku-prompt"
API="https://api.github.com"
GITHUB_REMOTE="github"

while [ $# -gt 0 ]; do
  case "$1" in
    check|build|publish-github)
      ACTION="$1"; shift ;;
    --tag)
      [ $# -ge 2 ] || die "--tag requires a value"
      TAG="$2"; shift 2 ;;
    --execute) EXECUTE=1; shift ;;
    --authorize-github) AUTHORIZE_GITHUB=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

VERSION_NAME="$(sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$PROJECT_ROOT/app/build.gradle.kts" | head -1)"
VERSION_CODE="$(sed -n 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$PROJECT_ROOT/app/build.gradle.kts" | head -1)"
EXPECTED_PACKAGE="$(sed -n 's/^[[:space:]]*applicationId[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$PROJECT_ROOT/app/build.gradle.kts" | head -1)"
[ -n "$VERSION_NAME" ] || die "cannot read versionName from app/build.gradle.kts"
[ -n "$VERSION_CODE" ] || die "cannot read versionCode from app/build.gradle.kts"
[ -n "$EXPECTED_PACKAGE" ] || die "cannot read applicationId from app/build.gradle.kts"
[ -n "$TAG" ] || TAG="v$VERSION_NAME"
[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]] || die "invalid release tag: $TAG"
[ "${TAG#v}" = "$VERSION_NAME" ] || die "tag $TAG does not match versionName $VERSION_NAME"

OUT_DIR="$PROJECT_ROOT/dist/release/$TAG"
NOTES_FILE="${TIKU_RELEASE_NOTES_FILE:-$PROJECT_ROOT/docs/releases/$TAG.md}"
LOCAL_JDK="${TIKU_RELEASE_JDK:-${LOCAL_JDK:-/usr/lib/jvm/java-17-openjdk-amd64}}"
LOCAL_SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
APKSIGNER="${TIKU_RELEASE_APKSIGNER:-$LOCAL_SDK/build-tools/35.0.0/apksigner}"
ZIPALIGN="${TIKU_RELEASE_ZIPALIGN:-$LOCAL_SDK/build-tools/35.0.0/zipalign}"
AAPT2="${TIKU_RELEASE_AAPT2:-$LOCAL_SDK/build-tools/35.0.0/aapt2}"
CLEANUP_DIR=""

cleanup() {
  if [ -n "$CLEANUP_DIR" ]; then
    rm -rf "$CLEANUP_DIR"
  fi
}
trap cleanup EXIT

require_clean_main() {
  [ -z "$(git status --porcelain --untracked-files=normal)" ] \
    || die "working tree is dirty; commit before building a release"
  local branch
  branch="$(git symbolic-ref --quiet --short HEAD || true)"
  [ "$branch" = "main" ] || die "release build must run from branch main (current: ${branch:-detached})"
}

require_tag_not_moved() {
  if git rev-parse --quiet --verify "refs/tags/$TAG" >/dev/null; then
    local tag_commit
    tag_commit="$(git rev-parse "$TAG^{commit}")"
    [ "$tag_commit" = "$(git rev-parse HEAD)" ] \
      || die "tag $TAG does not point at HEAD"
  fi
}

require_notes() {
  [ -f "$NOTES_FILE" ] || die "release notes not found: $NOTES_FILE"
  [ -s "$NOTES_FILE" ] || die "release notes are empty: $NOTES_FILE"
  local abs rel
  abs="$(realpath -m -- "$NOTES_FILE")"
  case "$abs" in
    "$PROJECT_ROOT"/*) ;;
    *) die "release notes must live inside the repository: $NOTES_FILE" ;;
  esac
  rel="${abs#"$PROJECT_ROOT"/}"
  git ls-files --error-unmatch -- "$rel" >/dev/null 2>&1 \
    || die "release notes must be committed: $rel"
  NOTES_FILE="$abs"
}

require_tools() {
  local c
  for c in git python3 sha256sum tar gzip curl; do
    command -v "$c" >/dev/null 2>&1 || die "missing command: $c"
  done
  [ -x "$LOCAL_JDK/bin/java" ] || die "JDK 17 not found: $LOCAL_JDK"
  [ -x "$LOCAL_JDK/bin/keytool" ] || die "keytool not found in $LOCAL_JDK"
  [ -x "$APKSIGNER" ] || die "apksigner not found: $APKSIGNER"
  [ -x "$ZIPALIGN" ] || die "zipalign not found: $ZIPALIGN"
  [ -x "$AAPT2" ] || die "aapt2 not found: $AAPT2"
}

normalize_sha256() {
  printf '%s' "$1" | tr -d ':' | tr -d '[:space:]' | tr 'A-F' 'a-f'
}

find_gitleaks() {
  if [ -n "${GITLEAKS_BIN:-}" ] && [ -x "${GITLEAKS_BIN:-}" ]; then
    printf '%s\n' "$GITLEAKS_BIN"
    return 0
  fi
  if command -v gitleaks >/dev/null 2>&1; then
    command -v gitleaks
    return 0
  fi
  if [ -n "${HOME:-}" ] && [ -x "$HOME/.local/bin/gitleaks" ]; then
    printf '%s\n' "$HOME/.local/bin/gitleaks"
    return 0
  fi
  return 1
}

run_publication_checks() {
  local bin
  bin="$(find_gitleaks)" || die "gitleaks is required for a release; set GITLEAKS_BIN or install gitleaks"
  GITLEAKS_BIN="$bin" "$PROJECT_ROOT/tools/publish.sh" check
}

require_release_keystore() {
  [ -n "${TIKU_RELEASE_KEYSTORE:-}" ] || die "TIKU_RELEASE_KEYSTORE is required"
  [ -n "${TIKU_RELEASE_KEY_ALIAS:-}" ] || die "TIKU_RELEASE_KEY_ALIAS is required"
  [ -n "$RELEASE_KEYSTORE_PASSWORD" ] || die "TIKU_RELEASE_KEYSTORE_PASSWORD is required"
  [ -n "$RELEASE_KEY_PASSWORD" ] || die "TIKU_RELEASE_KEY_PASSWORD is required"
  [ -n "${TIKU_RELEASE_CERT_SHA256:-}" ] || die "TIKU_RELEASE_CERT_SHA256 is required"

  local ks
  ks="$(realpath -m -- "$TIKU_RELEASE_KEYSTORE")"
  [ -f "$ks" ] || die "release keystore not found: $ks"
  [ -r "$ks" ] || die "release keystore is not readable: $ks"
  case "$ks" in
    "$PROJECT_ROOT"/*) die "release keystore must live outside the repository: $ks" ;;
  esac
  KS_PATH="$ks"

  local expected
  expected="$(normalize_sha256 "$TIKU_RELEASE_CERT_SHA256")"
  [[ "$expected" =~ ^[0-9a-f]{64}$ ]] || die "TIKU_RELEASE_CERT_SHA256 must be a SHA-256 fingerprint"
  EXPECTED_CERT="$expected"

}

check_keystore_alias() {
  TIKU_RELEASE_KEYSTORE_PASSWORD="$RELEASE_KEYSTORE_PASSWORD" \
    "$LOCAL_JDK/bin/keytool" -list \
    -keystore "$KS_PATH" \
    -storepass:env TIKU_RELEASE_KEYSTORE_PASSWORD \
    -alias "$TIKU_RELEASE_KEY_ALIAS" >/dev/null 2>&1 \
    || die "release keystore, alias, or keystore password is invalid"
}

signing_certificate() {
  local apk="$1" certs expected got
  certs="$(PATH="$LOCAL_JDK/bin:$PATH" "$APKSIGNER" verify --print-certs "$apk" 2>/dev/null)" \
    || die "APK signature verification failed"
  if printf '%s\n' "$certs" | grep -q 'CN=Android Debug'; then
    die "APK is signed with an Android debug certificate"
  fi
  [ "$(printf '%s\n' "$certs" | grep -c 'certificate SHA-256 digest')" -eq 1 ] \
    || die "APK must have exactly one signer"
  got="$(printf '%s\n' "$certs" | awk -F': ' '/certificate SHA-256 digest/ {print $NF; exit}')"
  got="$(normalize_sha256 "$got")"
  [[ "$got" =~ ^[0-9a-f]{64}$ ]] || die "could not read the APK signer certificate fingerprint"
  expected="$(normalize_sha256 "$TIKU_RELEASE_CERT_SHA256")"
  [ "$got" = "$expected" ] || die "APK signer certificate does not match TIKU_RELEASE_CERT_SHA256"
  SIGNER_SHA256="$got"
}

verify_signed_apk() {
  local apk="$1" badging pkg vc vn perms
  [ -f "$apk" ] || die "signed APK not found: $apk"
  "$ZIPALIGN" -c 4 "$apk" >/dev/null 2>&1 || die "APK is not zip-aligned"
  signing_certificate "$apk"

  badging="$("$AAPT2" dump badging "$apk" 2>/dev/null | head -1 || true)"
  pkg="$(printf '%s\n' "$badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1)"
  vc="$(printf '%s\n' "$badging" | sed -n 's/^.* versionCode='"'"'\([^'"'"']*\)'"'"'.*/\1/p' | head -1)"
  vn="$(printf '%s\n' "$badging" | sed -n 's/^.* versionName='"'"'\([^'"'"']*\)'"'"'.*/\1/p' | head -1)"
  [ "$pkg" = "$EXPECTED_PACKAGE" ] || die "APK package is $pkg, expected $EXPECTED_PACKAGE"
  [ "$vc" = "$VERSION_CODE" ] || die "APK versionCode is $vc, expected $VERSION_CODE"
  [ "$vn" = "$VERSION_NAME" ] || die "APK versionName is $vn, expected $VERSION_NAME"
  if printf '%s\n' "$badging" | grep -q 'application-debuggable'; then
    die "APK is debuggable"
  fi
  perms="$("$AAPT2" dump permissions "$apk" 2>/dev/null \
    | sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p" | paste -sd, -)"
  if printf '%s' "$perms" | tr ',' '\n' | grep -qx 'android.permission.INTERNET'; then
    die "APK declares android.permission.INTERNET"
  fi
  APK_PERMISSIONS="$perms"
}

build_release() {
  require_clean_main
  require_tag_not_moved
  require_notes
  require_tools
  require_release_keystore
  check_keystore_alias
  run_publication_checks

  info "building unsigned release APK on the remote builder"
  "$PROJECT_ROOT/tools/build_remote.sh" --release --warm --test

  local unsigned="$PROJECT_ROOT/dist/tiku-prompt-release-unsigned.apk"
  [ -f "$unsigned" ] || die "remote build did not produce $unsigned"

  rm -rf "$OUT_DIR"
  mkdir -p "$OUT_DIR"
  local tmp_signed="$OUT_DIR/.release-signing.tmp.apk"
  local signed="$OUT_DIR/tiku-prompt-$TAG.apk"
  local source_archive="$OUT_DIR/tiku-prompt-$TAG-source.tar.gz"

  info "signing release APK with the configured keystore"
  rm -f "$tmp_signed"
  if ! TIKU_RELEASE_KEYSTORE_PASSWORD="$RELEASE_KEYSTORE_PASSWORD" \
    TIKU_RELEASE_KEY_PASSWORD="$RELEASE_KEY_PASSWORD" \
    PATH="$LOCAL_JDK/bin:$PATH" "$APKSIGNER" sign \
    --ks "$KS_PATH" \
    --ks-key-alias "$TIKU_RELEASE_KEY_ALIAS" \
    --ks-pass "env:TIKU_RELEASE_KEYSTORE_PASSWORD" \
    --key-pass "env:TIKU_RELEASE_KEY_PASSWORD" \
    --out "$tmp_signed" "$unsigned" </dev/null; then
    rm -f "$tmp_signed" "$tmp_signed.idsig"
    die "apksigner failed"
  fi
  # apksigner may emit a v4 .idsig sidecar; it is not a release artifact.
  rm -f "$tmp_signed.idsig"
  verify_signed_apk "$tmp_signed"
  mv "$tmp_signed" "$signed"

  info "creating source archive from HEAD"
  git archive --format=tar.gz --prefix="tiku-prompt-$TAG/" -o "$source_archive" HEAD \
    || die "git archive failed"

  local apk_sha source_sha
  apk_sha="$(sha256sum "$signed" | awk '{print $1}')"
  source_sha="$(sha256sum "$source_archive" | awk '{print $1}')"
  {
    printf 'tag=%s\n' "$TAG"
    printf 'versionName=%s\n' "$VERSION_NAME"
    printf 'versionCode=%s\n' "$VERSION_CODE"
    printf 'commit=%s\n' "$(git rev-parse HEAD)"
    printf 'package=%s\n' "$EXPECTED_PACKAGE"
    printf 'apk=%s\n' "$(basename "$signed")"
    printf 'apk_sha256=%s\n' "$apk_sha"
    printf 'source=%s\n' "$(basename "$source_archive")"
    printf 'source_sha256=%s\n' "$source_sha"
    printf 'signer_cert_sha256=%s\n' "$SIGNER_SHA256"
    printf 'permissions=%s\n' "${APK_PERMISSIONS:-}"
    printf 'built_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  } > "$OUT_DIR/release-info.txt"
  (
    cd "$OUT_DIR"
    sha256sum "$(basename "$signed")" "$(basename "$source_archive")" > SHA256SUMS
  )
  chmod 644 "$signed" "$source_archive" "$OUT_DIR/SHA256SUMS" "$OUT_DIR/release-info.txt"
  verify_release_metadata
  verify_source_archive

  ok "release artifacts prepared in $OUT_DIR"
  printf '  APK:    %s\n' "$signed"
  printf '  source: %s\n' "$source_archive"
  printf '  sha256: %s\n' "$apk_sha"
  printf '  signer: %s\n' "$SIGNER_SHA256"
}

github_auth_init() {
  [ -n "$RELEASE_GH_TOKEN" ] || die "GH_TOKEN is required; use a token owned by pchartming"
  [[ "$RELEASE_GH_TOKEN" =~ ^[A-Za-z0-9_]+$ ]] \
    || die "GH_TOKEN contains unsupported characters"
  CLEANUP_DIR="$(mktemp -d)" || die "mktemp failed"
  chmod 700 "$CLEANUP_DIR"
  AUTH_CFG="$CLEANUP_DIR/curl.auth"
  local old_umask
  old_umask="$(umask)"
  umask 077
  printf 'header = "Authorization: Bearer %s"\n' "$RELEASE_GH_TOKEN" > "$AUTH_CFG"
  chmod 600 "$AUTH_CFG"
  umask "$old_umask"
  unset RELEASE_GH_TOKEN

  local user_json="$CLEANUP_DIR/user.json"
  curl -fsS --max-time 60 --retry 2 --retry-delay 2 \
    --config "$AUTH_CFG" \
    -H 'Accept: application/vnd.github+json' \
    -H 'X-GitHub-Api-Version: 2022-11-28' \
    "$API/user" -o "$user_json" \
    || die "GitHub token check failed"
  local login
  login="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("login",""))' "$user_json")"
  [ "$login" = "pchartming" ] || die "GH_TOKEN is not authorized for the pchartming account"
}

github_api() {
  curl -fsS --max-time 120 \
    --config "$AUTH_CFG" \
    -H 'Accept: application/vnd.github+json' \
    -H 'X-GitHub-Api-Version: 2022-11-28' \
    "$@"
}

release_info_value() {
  sed -n "s/^$1=//p" "$OUT_DIR/release-info.txt" | head -1
}

verify_release_metadata() {
  local apk="$OUT_DIR/tiku-prompt-$TAG.apk"
  local source="$OUT_DIR/tiku-prompt-$TAG-source.tar.gz"
  local apk_sha source_sha
  apk_sha="$(sha256sum "$apk" | awk '{print $1}')"
  source_sha="$(sha256sum "$source" | awk '{print $1}')"

  [ "$(release_info_value tag)" = "$TAG" ] || die "release-info tag does not match"
  [ "$(release_info_value versionName)" = "$VERSION_NAME" ] || die "release-info versionName does not match"
  [ "$(release_info_value versionCode)" = "$VERSION_CODE" ] || die "release-info versionCode does not match"
  [ "$(release_info_value commit)" = "$(git rev-parse HEAD)" ] \
    || die "release-info commit does not match HEAD; rebuild the release"
  [ "$(release_info_value package)" = "$EXPECTED_PACKAGE" ] || die "release-info package does not match"
  [ "$(release_info_value apk)" = "$(basename "$apk")" ] || die "release-info APK name does not match"
  [ "$(release_info_value source)" = "$(basename "$source")" ] || die "release-info source name does not match"
  [ "$(release_info_value apk_sha256)" = "$apk_sha" ] || die "release-info APK checksum does not match"
  [ "$(release_info_value source_sha256)" = "$source_sha" ] || die "release-info source checksum does not match"
  [ "$(normalize_sha256 "$(release_info_value signer_cert_sha256)")" = "$SIGNER_SHA256" ] \
    || die "release-info signer does not match the APK"
}

verify_source_archive() {
  local source="$OUT_DIR/tiku-prompt-$TAG-source.tar.gz" fresh fresh_sha archive_sha
  fresh="$(mktemp)" || die "mktemp failed"
  if ! git archive --format=tar.gz --prefix="tiku-prompt-$TAG/" -o "$fresh" HEAD; then
    rm -f "$fresh"
    die "failed to create a verification source archive"
  fi
  fresh_sha="$(sha256sum "$fresh" | awk '{print $1}')"
  rm -f "$fresh"
  archive_sha="$(sha256sum "$source" | awk '{print $1}')"
  [ "$fresh_sha" = "$archive_sha" ] \
    || die "source archive does not match HEAD; rebuild the release"
}

require_release_assets() {
  [ -f "$OUT_DIR/tiku-prompt-$TAG.apk" ] || die "signed APK missing; run build first"
  [ -f "$OUT_DIR/tiku-prompt-$TAG-source.tar.gz" ] || die "source archive missing; run build first"
  [ -f "$OUT_DIR/SHA256SUMS" ] || die "SHA256SUMS missing; run build first"
  [ -f "$OUT_DIR/release-info.txt" ] || die "release-info.txt missing; run build first"
  ( cd "$OUT_DIR" && sha256sum -c SHA256SUMS >/dev/null ) \
    || die "release artifact checksum verification failed"
  require_release_keystore
  verify_signed_apk "$OUT_DIR/tiku-prompt-$TAG.apk"
  verify_release_metadata
  verify_source_archive
}

require_github_remote() {
  local url
  url="$(git remote get-url "$GITHUB_REMOTE" 2>/dev/null || true)"
  [ -n "$url" ] || die "GitHub remote '$GITHUB_REMOTE' is not configured"
  case "$url" in
    *github.com*|*github-pchartming*) ;;
    *) die "GitHub remote does not look like a GitHub URL: $url" ;;
  esac
  case "$url" in
    *pchartming/tiku-prompt*|*pchartming/tiku-prompt.git*) ;;
    *) die "GitHub remote is not pchartming/tiku-prompt: $url" ;;
  esac
}

require_source_on_github() {
  local remote_main head
  head="$(git rev-parse HEAD)"
  remote_main="$(git ls-remote --heads "$GITHUB_REMOTE" refs/heads/main | awk 'NR==1 {print $1}')" \
    || die "failed to query $GITHUB_REMOTE/main"
  [ -n "$remote_main" ] || die "$GITHUB_REMOTE/main does not exist"
  [ "$remote_main" = "$head" ] \
    || die "$GITHUB_REMOTE/main is not at HEAD; publish source first with tools/publish.sh"
}

read_remote_tag() {
  local lines peeled
  lines="$(git ls-remote --tags "$GITHUB_REMOTE" "refs/tags/$TAG" "refs/tags/$TAG^{}")" \
    || die "failed to query $GITHUB_REMOTE tags"
  [ -n "$lines" ] || { REMOTE_TAG_COMMIT=""; return 0; }
  peeled="$(printf '%s\n' "$lines" | awk '/\^\{\}$/ {print $1}' | head -1)"
  if [ -z "$peeled" ]; then
    peeled="$(printf '%s\n' "$lines" | awk '{print $1}' | head -1)"
  fi
  REMOTE_TAG_COMMIT="$peeled"
}

publish_github() {
  [ "$AUTHORIZE_GITHUB" = 1 ] \
    || die "GitHub release publishing requires explicit authorization: add --authorize-github"
  [ -n "$RELEASE_GH_TOKEN" ] || die "GH_TOKEN is required; use a token owned by pchartming"
  require_clean_main
  require_tag_not_moved
  require_notes
  require_tools
  require_github_remote
  require_release_assets
  require_source_on_github

  local gitleaks
  gitleaks="$(find_gitleaks)" || die "gitleaks is required for a public release"
  GITLEAKS_BIN="$gitleaks" "$PROJECT_ROOT/tools/publish.sh" check
  github_auth_init

  local release_json="$CLEANUP_DIR/release-check.json" http
  http="$(curl -sS --max-time 60 --config "$AUTH_CFG" \
    -H 'Accept: application/vnd.github+json' \
    -H 'X-GitHub-Api-Version: 2022-11-28' \
    -o "$release_json" -w '%{http_code}' \
    "$API/repos/$GITHUB_REPO/releases/tags/$TAG")" \
    || die "failed to query existing GitHub releases"
  case "$http" in
    200) die "GitHub release $TAG already exists" ;;
    404) ;;
    *) die "unexpected GitHub API status while checking release: $http" ;;
  esac

  local repo_json="$CLEANUP_DIR/repo.json" visibility
  github_api "$API/repos/$GITHUB_REPO" -o "$repo_json" \
    || die "cannot read repository metadata"
  visibility="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("visibility",""))' "$repo_json")"
  [ "$visibility" = "public" ] || warn "GitHub repository is not public; the release will not be public"

  if git rev-parse --quiet --verify "refs/tags/$TAG" >/dev/null; then
    [ "$(git rev-parse "$TAG^{commit}")" = "$(git rev-parse HEAD)" ] \
      || die "local tag $TAG does not point at HEAD"
  elif [ "$EXECUTE" = 1 ]; then
    git tag -a "$TAG" -m "Tiku Prompt $TAG" || die "failed to create tag $TAG"
    ok "created annotated tag $TAG"
  else
    info "would create annotated tag $TAG"
  fi

  read_remote_tag
  if [ -n "$REMOTE_TAG_COMMIT" ]; then
    [ "$REMOTE_TAG_COMMIT" = "$(git rev-parse HEAD)" ] \
      || die "remote tag $TAG does not point at HEAD"
  elif [ "$EXECUTE" = 1 ]; then
    git push "$GITHUB_REMOTE" "refs/tags/$TAG" || die "failed to push tag $TAG"
    ok "pushed tag $TAG (no force)"
  else
    info "would push tag $TAG to $GITHUB_REMOTE"
  fi

  local apk="$OUT_DIR/tiku-prompt-$TAG.apk"
  local source_archive="$OUT_DIR/tiku-prompt-$TAG-source.tar.gz"
  local assets=(
    "$apk"
    "$source_archive"
    "$OUT_DIR/SHA256SUMS"
    "$OUT_DIR/release-info.txt"
  )

  if [ "$EXECUTE" != 1 ]; then
    info "dry run only; would create GitHub draft release $TAG and upload:"
    local asset
    for asset in "${assets[@]}"; do
      printf '  %s\n' "$(basename "$asset")"
    done
    info "add --execute to push the tag and create the release"
    return 0
  fi

  local payload="$CLEANUP_DIR/release-payload.json"
  python3 - "$TAG" "$NOTES_FILE" > "$payload" <<'PY'
import json
import sys

tag = sys.argv[1]
with open(sys.argv[2], encoding="utf-8") as fh:
    body = fh.read()
print(json.dumps({
    "tag_name": tag,
    "name": f"Tiku Prompt {tag}",
    "body": body,
    "draft": True,
    "prerelease": "-" in tag,
}))
PY

  local created="$CLEANUP_DIR/release-created.json"
  github_api -X POST -H 'Content-Type: application/json' \
    --data-binary @"$payload" \
    "$API/repos/$GITHUB_REPO/releases" -o "$created" \
    || die "failed to create draft GitHub release"

  local release_id upload_url html_url
  release_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$created")"
  upload_url="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["upload_url"].split("{")[0])' "$created")"
  html_url="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("html_url",""))' "$created")"
  local release_endpoint="$API/repos/$GITHUB_REPO/releases/$release_id"

  local asset name encoded type
  for asset in "${assets[@]}"; do
    name="$(basename "$asset")"
    case "$name" in
      *.apk) type="application/vnd.android.package-archive" ;;
      *.tar.gz) type="application/gzip" ;;
      *) type="text/plain" ;;
    esac
    encoded="$(python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$name")"
    github_api -X POST \
      -H "Content-Type: $type" \
      --data-binary @"$asset" \
      "$upload_url?name=$encoded" -o "$CLEANUP_DIR/asset.json" \
      || die "failed to upload $name; draft release remains at $html_url"
    ok "uploaded $name"
  done

  printf '{"draft":false}' > "$CLEANUP_DIR/publish-payload.json"
  github_api -X PATCH -H 'Content-Type: application/json' \
    --data-binary @"$CLEANUP_DIR/publish-payload.json" \
    "$release_endpoint" -o "$CLEANUP_DIR/release-published.json" \
    || die "release assets uploaded but publishing the draft failed: $html_url"

  html_url="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("html_url",""))' "$CLEANUP_DIR/release-published.json")"
  ok "published GitHub release: $html_url"
}

case "$ACTION" in
  check)
    require_clean_main
    require_tag_not_moved
    require_notes
    require_tools
    require_release_keystore
    check_keystore_alias
    run_publication_checks
    ok "release readiness check passed for $TAG"
    printf '  version: %s (code %s)\n' "$VERSION_NAME" "$VERSION_CODE"
    printf '  notes:   %s\n' "$NOTES_FILE"
    printf '  output:  %s\n' "$OUT_DIR"
    ;;
  build)
    build_release
    ;;
  publish-github)
    publish_github
    ;;
esac
