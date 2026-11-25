#!/usr/bin/env bash
# Safe publication entry point for the Tiku Prompt repository.
#
# The default target is the private upstream "origin". Nothing is pushed
# without --execute. GitHub and Gitee are never contacted unless their explicit
# authorization flag is supplied; public GitHub pushes additionally require
# --confirm-public. The script never force-pushes, creates tags/releases, builds
# APKs, or changes repository visibility.
#
# Usage:
#   tools/publish.sh [check] [--worktree]
#   tools/publish.sh push-private [--execute]
#   tools/publish.sh push-github --authorize-github
#                    [--visibility private|public] [--execute] [--confirm-public]
#   tools/publish.sh push-gitee --authorize-gitee
#                    [--execute] [--confirm-mirror]
#
# push-gitee mirrors the source main branch only. Release tags and assets are
# mirrored by tools/release.sh publish-gitee after a GitHub Release exists.
#
# Environment:
#   GITLEAKS_BIN   Optional path to a gitleaks binary. Public pushes require it.
#
# "origin" is the private upstream. The GitHub remote must point at
# pchartming/tiku-prompt. The optional Gitee remote must point at
# pchartming/tiku-prompt and is only updated after github/main equals HEAD.
# Those two targets are opt-in by explicit authorization.
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

if [ -t 1 ]; then
  C_RESET=$'\033[0m'; C_RED=$'\033[0;31m'; C_GREEN=$'\033[0;32m'
  C_YELLOW=$'\033[1;33m'; C_CYAN=$'\033[0;36m'
else
  C_RESET=""; C_RED=""; C_GREEN=""; C_YELLOW=""; C_CYAN=""
fi
info() { printf '%s[publish]%s %s\n' "$C_CYAN" "$C_RESET" "$*"; }
ok()   { printf '%s[publish] [OK]%s %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn() { printf '%s[publish] [!]%s %s\n' "$C_YELLOW" "$C_RESET" "$*" >&2; }
die()  { printf '%s[publish] [x]%s %s\n' "$C_RED" "$C_RESET" "$*" >&2; exit 1; }

usage() {
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"
}

command -v git >/dev/null 2>&1 || die "git is required"
command -v python3 >/dev/null 2>&1 || die "python3 is required"
[ -f tools/publication_check.py ] || die "missing tools/publication_check.py"

ACTION="push-private"
WORKTREE=0
EXECUTE=0
VISIBILITY="private"
CONFIRM_PUBLIC=0
CONFIRM_MIRROR=0
AUTHORIZE_GITHUB=0
AUTHORIZE_GITEE=0

while [ $# -gt 0 ]; do
  case "$1" in
    check|push-private|push-github|push-gitee)
      ACTION="$1"; shift ;;
    --worktree) WORKTREE=1; shift ;;
    --execute) EXECUTE=1; shift ;;
    --visibility)
      [ $# -ge 2 ] || die "--visibility requires private or public"
      VISIBILITY="$2"; shift 2 ;;
    --confirm-public) CONFIRM_PUBLIC=1; shift ;;
    --confirm-mirror) CONFIRM_MIRROR=1; shift ;;
    --authorize-github) AUTHORIZE_GITHUB=1; shift ;;
    --authorize-gitee) AUTHORIZE_GITEE=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

case "$VISIBILITY" in
  private|public) ;;
  *) die "--visibility must be private or public" ;;
esac

if [ "$EXECUTE" = 1 ]; then
  [ -z "$(git status --porcelain --untracked-files=normal)" ] \
    || die "working tree is dirty; commit or stash changes before publishing"
  BRANCH="$(git symbolic-ref --quiet --short HEAD || true)"
  [ "$BRANCH" = "main" ] || die "publishing must run from branch main (current: ${BRANCH:-detached})"
fi

DENYLIST_ARGS=()
if [ -f .publish-denylist.txt ]; then
  DENYLIST_ARGS=(--denylist .publish-denylist.txt)
fi

run_builtin_check() {
  local ref="$1"
  info "running built-in publication scan"
  if [ -n "$ref" ]; then
    python3 tools/publication_check.py --ref "$ref" --history "${DENYLIST_ARGS[@]}"
  else
    python3 tools/publication_check.py "${DENYLIST_ARGS[@]}"
  fi
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

run_gitleaks() {
  local require="$1" bin tmp
  if ! bin="$(find_gitleaks)"; then
    if [ "$require" = 1 ]; then
      die "gitleaks is required for a public push; set GITLEAKS_BIN or install gitleaks"
    fi
    warn "gitleaks not found; built-in scan passed, but installing gitleaks is recommended"
    return 0
  fi
  tmp="$(mktemp -d)" || die "mktemp failed"
  trap 'rm -rf "$tmp"' RETURN
  git archive HEAD | tar -x -C "$tmp" || die "failed to export HEAD for gitleaks"
  info "running gitleaks on exported HEAD"
  if ! "$bin" detect --no-git --source "$tmp" --redact --exit-code 1 >/dev/null 2>"$tmp/gitleaks.err"; then
    # Never print the report: it can contain the secret itself.
    die "gitleaks found potential secrets (details intentionally suppressed)"
  fi
  rm -rf "$tmp"
  trap - RETURN
  ok "gitleaks scan passed"
}

remote_url() {
  git remote get-url "$1" 2>/dev/null || true
}

check_private_remote() {
  local url
  url="$(remote_url origin)"
  [ -n "$url" ] || die "private remote 'origin' is not configured"
  case "$url" in
    *github.com*|*gitee.com*)
      die "origin looks like GitHub/Gitee; use the explicitly authorized push-github or push-gitee action" ;;
  esac
  ok "private upstream: origin"
}

check_github_remote() {
  local url
  url="$(remote_url github)"
  [ -n "$url" ] || die "GitHub remote 'github' is not configured"
  case "$url" in
    *github.com*|*github-pchartming*) ;;
    *) die "GitHub remote does not look like a GitHub URL: $url" ;;
  esac
  case "$url" in
    *pchartming/tiku-prompt*|*pchartming/tiku-prompt.git*) ;;
    *) die "GitHub remote is not pchartming/tiku-prompt: $url" ;;
  esac
}

check_gitee_remote() {
  local url
  url="$(remote_url gitee)"
  [ -n "$url" ] || die "Gitee remote 'gitee' is not configured"
  case "$url" in
    *gitee.com*|*gitee.com:*) ;;
    *) die "Gitee remote does not look like a Gitee URL: $url" ;;
  esac
  case "$url" in
    *tiku-prompt*|*tiku-prompt.git*) ;;
    *) die "Gitee remote does not point at a tiku-prompt repository: $url" ;;
  esac
}

require_github_main_at_head() {
  local remote_lines remote_main head
  check_github_remote
  head="$(git rev-parse HEAD)"
  remote_lines="$(git ls-remote --heads github refs/heads/main)" \
    || die "failed to query github/main; Gitee is a mirror of GitHub"
  remote_main="$(printf '%s\n' "$remote_lines" | awk 'NR==1 {print $1}')"
  [ -n "$remote_main" ] || die "github/main does not exist; publish source to GitHub first"
  [ "$remote_main" = "$head" ] \
    || die "github/main is not at HEAD; publish source to GitHub before mirroring to Gitee"
}

preflight_push() {
  local require_gitleaks="$1" remote="$2" remote_lines remote_head
  [ "$EXECUTE" = 1 ] || info "dry run only; add --execute to push"
  run_builtin_check HEAD
  run_gitleaks "$require_gitleaks"
  remote_lines="$(git ls-remote --heads "$remote" refs/heads/main)" \
    || die "failed to query ${remote}/main; refusing to push"
  if [ -n "$remote_lines" ]; then
    remote_head="$(printf '%s\n' "$remote_lines" | awk 'NR==1 {print $1}')"
    git fetch --quiet "$remote" main || die "failed to fetch ${remote}/main; refusing to push"
    git cat-file -e "${remote_head}^{commit}" 2>/dev/null \
      || die "fetched commit for ${remote}/main is unavailable; refusing to push"
    if ! git merge-base --is-ancestor "$remote_head" HEAD; then
      die "remote main is not an ancestor of HEAD; refusing a non-fast-forward push"
    fi
  else
    info "remote main does not exist yet; the push would create it"
  fi
}

push_branch() {
  local remote="$1"
  git push "$remote" HEAD:refs/heads/main
  ok "pushed $(git rev-parse --short=12 HEAD) to $remote/main (no force)"
}

case "$ACTION" in
  check)
    if [ "$WORKTREE" = 1 ]; then
      run_builtin_check ""
    else
      run_builtin_check HEAD
    fi
    run_gitleaks 0
    ok "publication check completed"
    ;;
  push-private)
    check_private_remote
    preflight_push 0 origin
    if [ "$EXECUTE" = 1 ]; then
      push_branch origin
    else
      info "would push HEAD to origin/main; no changes made"
    fi
    ;;
  push-github)
    [ "$AUTHORIZE_GITHUB" = 1 ] \
      || die "GitHub publishing requires explicit authorization: add --authorize-github"
    [ "$VISIBILITY" = "public" ] || [ "$EXECUTE" = 0 ] || info "pushing to a repository expected to remain private"
    if [ "$VISIBILITY" = "public" ]; then
      [ "$CONFIRM_PUBLIC" = 1 ] || die "public push requires --confirm-public after reviewing the scan"
      [ "$EXECUTE" = 1 ] || info "public dry run; add --execute --confirm-public to push"
    fi
    check_github_remote
    preflight_push "$([ "$VISIBILITY" = "public" ] && echo 1 || echo 0)" github
    if [ "$EXECUTE" = 1 ]; then
      push_branch github
    else
      info "would push HEAD to github/main; no changes made"
    fi
    ;;
  push-gitee)
    [ "$AUTHORIZE_GITEE" = 1 ] \
      || die "Gitee mirroring requires explicit authorization: add --authorize-gitee"
    [ "$EXECUTE" = 0 ] || [ "$CONFIRM_MIRROR" = 1 ] \
      || die "Gitee mirroring requires --confirm-mirror"
    require_github_main_at_head
    check_gitee_remote
    preflight_push 0 gitee
    if [ "$EXECUTE" = 1 ]; then
      push_branch gitee
    else
      info "would mirror the GitHub-published HEAD to gitee/main; no changes made"
    fi
    ;;
esac
