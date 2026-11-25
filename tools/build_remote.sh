#!/usr/bin/env bash
# Optional remote build helper for low-memory development machines.
#
# The script syncs this repository to a Linux builder over SSH, runs Gradle
# there, and copies the debug APK and unit-test report back to dist/. It does
# not modify the builder's source tree, use sudo, or run Git on the builder.
#
# Configuration is read from the environment, or from a gitignored .env.build
# file in the repository root:
#
#   TIKU_BUILD_SSH=user@host              SSH target (required)
#   TIKU_BUILD_ROOT=~/tiku-build          Dedicated remote workspace root
#   TIKU_BUILD_TOOLCHAIN=~/android-env    Directory containing jdk17/ and android-sdk/
#   TIKU_BUILD_GRADLE_BIN=~/gradle/bin/gradle
#                                         Optional Gradle 8.14.3 executable
#   TIKU_BUILD_ENV_FILE=/path/to/env      Alternate env file
#
# The remote directories are dedicated to this project. Set them explicitly;
# the helper never reuses or deletes another project's build directory.
#
# Usage:
#   ./tools/build_remote.sh [options]
#
#   --test              Also run :app:testDebugUnitTest
#   --warm              Reuse the remote warm workspace and Gradle daemon
#   --reset-warm        Delete the warm workspace, then exit without building
#   --check             Check the remote toolchain and workspace, without building
#   --prune             Remove workspaces/results older than 3 days, then exit
#   --ssh user@host     Build machine (or set TIKU_BUILD_SSH)
#   --remote-root DIR   Dedicated remote root (or set TIKU_BUILD_ROOT)
#   --toolchain-root DIR Directory with jdk17/ and android-sdk/ (or env)
#   --gradle-bin PATH   Gradle 8.14.3 executable (or env)
#   --workers N         Gradle workers (default 6)
#   --wait SECONDS      Remote build-lock timeout (default 900)
#   --proxy             Source ~/proxy.sh on on the builder (default: direct)
#   --keep              Keep remote run/result directories for troubleshooting
#
# Exit codes: 0 success; 2 usage; 3 local preflight; 4 remote unreachable or
# incomplete toolchain; 75 build-lock timeout; 6 build/test failure;
# 7 artifact fetch or verification failure.
#
# The local machine never runs Gradle; only fetched results are written to dist/.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Optional local-only configuration; never committed (see .gitignore).
ENV_FILE="${TIKU_BUILD_ENV_FILE:-$PROJECT_ROOT/.env.build}"
if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
fi

if [ -t 1 ]; then
  C_RESET=$'\033[0m'; C_CYAN=$'\033[0;36m'; C_GREEN=$'\033[0;32m'
  C_YELLOW=$'\033[1;33m'; C_RED=$'\033[0;31m'
else
  C_RESET=""; C_CYAN=""; C_GREEN=""; C_YELLOW=""; C_RED=""
fi

log()   { printf '%s[netbuild]%s %s\n' "$C_CYAN"   "$C_RESET" "$*"; }
ok()    { printf '%s[netbuild] [OK]%s %s\n' "$C_GREEN"  "$C_RESET" "$*"; }
warn()  { printf '%s[netbuild] [!]%s %s\n'  "$C_YELLOW" "$C_RESET" "$*" >&2; }
error() { printf '%s[netbuild] [x]%s %s\n'  "$C_RED"    "$C_RESET" "$*" >&2; }

die() {
  local code="$1"; shift
  error "$*"
  exit "$code"
}

usage() {
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"
}

# ---------------------------------------------------------------- 参数

WARM=0
DO_TEST=0
DO_CHECK=0
DO_PRUNE=0
DO_RESET_WARM=0
KEEP=0
PROXY=0
WORKERS=6
WAIT=900
REMOTE_SSH="${TIKU_BUILD_SSH:-}"
REMOTE_ROOT_DISPLAY="${TIKU_BUILD_ROOT:-~/tiku-build}"
TOOLCHAIN_DISPLAY="${TIKU_BUILD_TOOLCHAIN:-}"
GRADLE_BIN_DISPLAY="${TIKU_BUILD_GRADLE_BIN:-}"
EXPECTED_PACKAGE="${TIKU_EXPECTED_PACKAGE:-$(sed -n 's/^[[:space:]]*applicationId[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$PROJECT_ROOT/app/build.gradle.kts" | head -1)}"

while [ $# -gt 0 ]; do
  case "$1" in
    --test)        DO_TEST=1; shift ;;
    --warm)        WARM=1; shift ;;
    --reset-warm)  DO_RESET_WARM=1; shift ;;
    --check)       DO_CHECK=1; shift ;;
    --prune)       DO_PRUNE=1; shift ;;
    --keep)        KEEP=1; shift ;;
    --proxy)       PROXY=1; shift ;;
    --ssh)
      [ $# -ge 2 ] || die 2 "--ssh 需要 user@host"
      REMOTE_SSH="$2"; shift 2 ;;
    --remote-root)
      [ $# -ge 2 ] || die 2 "--remote-root 需要目录"
      REMOTE_ROOT_DISPLAY="$2"; shift 2 ;;
    --toolchain-root)
      [ $# -ge 2 ] || die 2 "--toolchain-root 需要目录"
      TOOLCHAIN_DISPLAY="$2"; shift 2 ;;
    --gradle-bin)
      [ $# -ge 2 ] || die 2 "--gradle-bin 需要路径"
      GRADLE_BIN_DISPLAY="$2"; shift 2 ;;
    --workers)
      [ $# -ge 2 ] || die 2 "--workers 需要数字"
      WORKERS="$2"; shift 2 ;;
    --wait)
      [ $# -ge 2 ] || die 2 "--wait 需要秒数"
      WAIT="$2"; shift 2 ;;
    -h|--help)
      usage; exit 0 ;;
    *)
      error "未知参数: $1"
      usage >&2
      exit 2 ;;
  esac
done

[[ "$WORKERS" =~ ^[1-9][0-9]*$ ]] || die 2 "--workers 必须是正整数：$WORKERS"
[[ "$WAIT" =~ ^[0-9]+$ ]] || die 2 "--wait 必须是非负整数：$WAIT"

if [ "$DO_CHECK" = 0 ] && [ "$DO_PRUNE" = 0 ] && [ "$DO_RESET_WARM" = 0 ]; then
  :
fi

# ---------------------------------------------------------------- 本地预检

LOCAL_JDK="${LOCAL_JDK:-/usr/lib/jvm/java-17-openjdk-amd64}"
LOCAL_SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
LOCAL_APKSIGNER="$LOCAL_SDK/build-tools/35.0.0/apksigner"
LOCAL_AAPT2="$LOCAL_SDK/build-tools/35.0.0/aapt2"

local_preflight() {
  local c
  for c in ssh scp rsync unzip sha256sum awk sed; do
    command -v "$c" >/dev/null 2>&1 || die 3 "本机缺少命令：$c"
  done
  [ -x "$PROJECT_ROOT/gradlew" ] || die 3 "未找到 $PROJECT_ROOT/gradlew"
  [ -f "$PROJECT_ROOT/settings.gradle.kts" ] || die 3 "未找到 settings.gradle.kts"
  [ -f "$PROJECT_ROOT/app/build.gradle.kts" ] || die 3 "未找到 app/build.gradle.kts"
  [ -n "$EXPECTED_PACKAGE" ] || die 3 "无法从 app/build.gradle.kts 读取 applicationId"

  local free_mb
  free_mb=$(df -Pm "$PROJECT_ROOT" | awk 'NR==2 {print $4}')
  if [ -n "$free_mb" ] && [ "$free_mb" -lt 500 ]; then
    warn "本机剩余磁盘 ${free_mb}MB，取回 APK 可能失败"
  fi
}

# ---------------------------------------------------------------- 远端连接与路径

SSH_OPTS=(
  -o BatchMode=yes
  -o ConnectTimeout=8
  -o ServerAliveInterval=20
  -o ServerAliveCountMax=6
  -o StrictHostKeyChecking=accept-new
  -o ControlPath=none
  -o ForwardAgent=no
)

REMOTE_HOME=""
REMOTE_ROOT_ABS=""
TOOLCHAIN_ABS=""
REMOTE_JDK=""
REMOTE_SDK=""
REMOTE_GRADLE_BIN=""
REMOTE_GRADLE_HOME=""
WARM_WS_REL="warm/tiku_project"
WARM_WS_ABS=""

expand_remote_path() {
  local value="$1"
  case "$value" in
    "~") printf '%s\n' "$REMOTE_HOME" ;;
    "~/"*) printf '%s\n' "$REMOTE_HOME/${value#\~/}" ;;
    /*) printf '%s\n' "$value" ;;
    *) return 1 ;;
  esac
}

remote_connect() {
  [ -n "$REMOTE_HOME" ] && return 0
  [ -n "$REMOTE_SSH" ] || die 2 "未配置构建机：请设置 TIKU_BUILD_SSH 或使用 --ssh user@host"
  [ -n "$TOOLCHAIN_DISPLAY" ] || die 2 "未配置工具链：请设置 TIKU_BUILD_TOOLCHAIN 或使用 --toolchain-root"
  REMOTE_HOME=$(ssh "${SSH_OPTS[@]}" "$REMOTE_SSH" 'printf %s "$HOME"' 2>/dev/null) \
    || die 4 "无法连接 $REMOTE_SSH（请确认免密登录与网络）"
  [ -n "$REMOTE_HOME" ] || die 4 "$REMOTE_SSH 返回了空 HOME"

  REMOTE_ROOT_ABS=$(expand_remote_path "$REMOTE_ROOT_DISPLAY") \
    || die 2 "--remote-root 必须以 ~/ 或 / 开头：$REMOTE_ROOT_DISPLAY"
  REMOTE_ROOT_ABS="${REMOTE_ROOT_ABS%/}"
  [ -n "$REMOTE_ROOT_ABS" ] || die 2 "远端根不能为空"
  [ "$REMOTE_ROOT_ABS" != "/" ] || die 2 "远端根不能是 /"
  [[ "$REMOTE_ROOT_ABS" =~ ^[A-Za-z0-9._/~-]+$ ]] \
    || die 2 "远端根包含不支持的字符：$REMOTE_ROOT_ABS"
  [ "$REMOTE_ROOT_ABS" != "$REMOTE_HOME" ] \
    || die 2 "远端根不能是 HOME 本身，请使用独立的构建目录"

  TOOLCHAIN_ABS=$(expand_remote_path "$TOOLCHAIN_DISPLAY") \
    || die 2 "--toolchain-root 必须以 ~/ 或 / 开头：$TOOLCHAIN_DISPLAY"
  TOOLCHAIN_ABS="${TOOLCHAIN_ABS%/}"
  REMOTE_JDK="$TOOLCHAIN_ABS/jdk17"
  REMOTE_SDK="$TOOLCHAIN_ABS/android-sdk"
  REMOTE_GRADLE_HOME="$REMOTE_ROOT_ABS/gradle-home"
  WARM_WS_ABS="$REMOTE_ROOT_ABS/$WARM_WS_REL"

  if [ -n "$GRADLE_BIN_DISPLAY" ]; then
    REMOTE_GRADLE_BIN=$(expand_remote_path "$GRADLE_BIN_DISPLAY") \
      || die 2 "--gradle-bin 必须以 ~/ 或 / 开头：$GRADLE_BIN_DISPLAY"
  else
    local found
    found=$(ssh "${SSH_OPTS[@]}" "$REMOTE_SSH" \
      "ls -d \"\$HOME\"/.gradle/wrapper/dists/gradle-8.14.3-bin/*/gradle-8.14.3/bin/gradle 2>/dev/null | head -1" \
      2>/dev/null) || true
    [ -n "$found" ] || die 4 "远端未找到 Gradle 8.14.3，可用 --gradle-bin 或 TIKU_BUILD_GRADLE_BIN 指定"
    REMOTE_GRADLE_BIN="$found"
  fi
}

remote_check() {
  log "检查远端工具链：$REMOTE_SSH"
  ssh "${SSH_OPTS[@]}" "$REMOTE_SSH" \
    "JDK='$REMOTE_JDK' SDK='$REMOTE_SDK' GRADLE_BIN='$REMOTE_GRADLE_BIN' ROOT='$REMOTE_ROOT_ABS' bash -s" <<'REMOTE_CHECK'
set -u
fail=0
say() { printf '  %s\n' "$*"; }
need() { [ -e "$2" ] || { printf '  [x] 缺少 %s：%s\n' "$1" "$2" >&2; fail=1; }; }

need "JDK17" "$JDK/bin/java"
need "Android SDK platform android-36" "$SDK/platforms/android-36"
need "Android build-tools 35.0.0" "$SDK/build-tools/35.0.0"
need "Gradle 8.14.3" "$GRADLE_BIN"
[ -d "$SDK/platform-tools" ] || say "[!] 未找到 platform-tools（不影响编译）"
[ -d "$SDK/cmdline-tools" ] || say "[!] 未找到 cmdline-tools（不影响已装 SDK 的编译）"

if [ -x "$JDK/bin/java" ]; then
  "$JDK/bin/java" -version 2>&1 | head -1 | sed 's/^/  /'
fi
if [ -x "$GRADLE_BIN" ]; then
  "$GRADLE_BIN" --version 2>/dev/null | awk '/^Gradle /{print "  "$0; exit}'
fi
if [ -d "$SDK/platforms/android-36" ]; then
  say "[OK] Android SDK platform-36"
fi
if [ -d "$SDK/build-tools/35.0.0" ]; then
  say "[OK] Android build-tools 35.0.0"
fi

avail_mb=$(df -Pm "$HOME" | awk 'NR==2 {print $4}')
say "磁盘可用：${avail_mb}MB（HOME）"
[ "${avail_mb:-0}" -ge 3000 ] || { printf '  [x] 可用磁盘不足 3GB\n' >&2; fail=1; }
say "CPU：$(nproc) 核"
free -m | awk 'NR==2 {printf "  内存：总计 %sMB，可用 %sMB\n", $2, $7}'
if [ -d "$ROOT/warm/tiku_project" ]; then
  say "warm 工作区：$(du -sh "$ROOT/warm/tiku_project" 2>/dev/null | awk '{print $1}')"
else
  say "warm 工作区：尚未创建"
fi
if [ -e "$SDK/licenses/android-sdk-license" ]; then
  say "[OK] Android SDK licenses"
else
  say "[!] 未找到 SDK licenses（AGP 若尝试安装缺失组件会失败）"
fi
exit "$fail"
REMOTE_CHECK
}

# ---------------------------------------------------------------- 远端维护

reset_warm() {
  [ -n "$WARM_WS_ABS" ] || die 2 "未解析远端路径"
  log "删除远端 warm 工作区：$REMOTE_SSH:$WARM_WS_ABS ..."
  ssh "${SSH_OPTS[@]}" "$REMOTE_SSH" "rm -rf '$WARM_WS_ABS'" \
    || die 4 "删除 warm 工作区失败"
  ok "warm 工作区已删除（下次 --warm 会冷启动一次）"
}

prune_remote() {
  log "清理 3 天前的远端 workspace/result ..."
  ssh "${SSH_OPTS[@]}" "$REMOTE_SSH" \
    "find '$REMOTE_ROOT_ABS/workspaces' -mindepth 1 -maxdepth 1 -type d -mtime +3 -exec rm -rf {} + 2>/dev/null; \
     find '$REMOTE_ROOT_ABS/results' -mindepth 1 -maxdepth 1 -type d -mtime +3 -exec rm -rf {} + 2>/dev/null" \
    || warn "远端清理失败（可忽略）"
  ok "远端清理完成"
}

# ---------------------------------------------------------------- 同步与远端 runner

prepare_remote_dirs() {
  ssh "${SSH_OPTS[@]}" "$REMOTE_SSH" \
    "mkdir -p '$REMOTE_ROOT_ABS/gradle-home' '$REMOTE_ROOT_ABS/workspaces' '$REMOTE_ROOT_ABS/results' '$REMOTE_ROOT_ABS/state'" \
    || die 4 "无法创建远端目录 $REMOTE_ROOT_ABS"
}

sync_source() {
  local ws_abs="$1"
  local -a excludes=(
    --exclude '.git/'
    --exclude 'build/'
    --exclude '.gradle/'
    --exclude '.kotlin/'
    --exclude 'local/'
    --exclude 'local.properties'
    --exclude 'dist/'
    --exclude 'tools/logs/'
    --exclude '.idea/'
    --exclude '*.iml'
    --exclude '.DS_Store'
    --exclude '.env.build'
    --exclude '.publish-denylist.txt'
    --exclude '__pycache__/'
    --exclude '*.pyc'
    --exclude '*.apk'
    --exclude '*.aab'
    --exclude '*.jks'
    --exclude '*.keystore'
    --exclude '*.p12'
    --exclude '*.pfx'
  )
  log "同步源码 -> 远端构建机（$([ "$WARM" = 1 ] && echo 'warm 增量' || echo '冷工作区')）..."
  ssh "${SSH_OPTS[@]}" "$REMOTE_SSH" "mkdir -p '$ws_abs'" \
    || die 4 "创建远端工作区失败"
  if [ "$WARM" = 1 ]; then
    ssh "${SSH_OPTS[@]}" "$REMOTE_SSH" "rm -rf '$ws_abs/local'" \
      || die 4 "清理 warm 工作区旧 local/ 失败"
  fi
  rsync -a --delete "${excludes[@]}" \
    "$PROJECT_ROOT/" "$REMOTE_SSH:$ws_abs/" \
    || die 4 "同步源码失败"
  ssh "${SSH_OPTS[@]}" "$REMOTE_SSH" "chmod 700 '$ws_abs'" \
    || die 4 "设置工作区权限失败"
}

write_remote_job() {
  local job="$1" ws_rel="$2" runid="$3"
  {
    printf 'ROOT=%q\n' "$REMOTE_ROOT_ABS"
    printf 'JDK=%q\n' "$REMOTE_JDK"
    printf 'SDK=%q\n' "$REMOTE_SDK"
    printf 'GRADLE_BIN=%q\n' "$REMOTE_GRADLE_BIN"
    printf 'WS_REL=%q\n' "$ws_rel"
    printf 'RUNID=%q\n' "$runid"
    printf 'WARM=%q\n' "$WARM"
    printf 'DO_TEST=%q\n' "$DO_TEST"
    printf 'PROXY=%q\n' "$PROXY"
    printf 'WAIT=%q\n' "$WAIT"
    printf 'WORKERS=%q\n' "$WORKERS"
    local args=":app:assembleDebug"
    [ "$DO_TEST" = 1 ] && args="$args :app:testDebugUnitTest"
    printf 'GRADLE_ARGS=(%s --build-cache --console=plain --stacktrace)\n' "$args"
    printf 'APK_REL=%q\n' 'app/build/outputs/apk/debug/app-debug.apk'
  } > "$job"
}

write_remote_runner() {
  local runner="$1"
  cat > "$runner" <<'REMOTE_RUN'
#!/usr/bin/env bash
set -uo pipefail
umask 077
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$HERE/job.env"

export JAVA_HOME="$JDK"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$ROOT/gradle-home"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/35.0.0:$PATH"

if [ "$PROXY" = 1 ]; then
  if [ -f "$HOME/proxy.sh" ]; then
    . "$HOME/proxy.sh" on
    echo "[remote] 已开启代理（~/proxy.sh on）"
  else
    echo "[remote] [x] 未找到 ~/proxy.sh，无法开启代理"
    exit 4
  fi
else
  unset http_proxy https_proxy all_proxy HTTP_PROXY HTTPS_PROXY ALL_PROXY
fi

mkdir -p "$ROOT/state" "$ROOT/results/$RUNID"
exec 9>"$ROOT/.build.lock"
if ! flock -w "$WAIT" 9; then
  echo "[remote] [x] 等待构建锁超时（${WAIT}s）"
  [ -f "$ROOT/state/lock.owner" ] && sed 's/^/[remote] 持有者: /' "$ROOT/state/lock.owner"
  exit 75
fi
{
  printf 'run=%s\n' "$RUNID"
  printf 'caller=%s\n' "$(hostname -s 2>/dev/null || echo unknown)"
  printf 'started=%s\n' "$(date -Is)"
  printf 'pid=%s\n' "$$"
} > "$ROOT/state/lock.owner"

WS_ABS="$ROOT/$WS_REL"
cleanup_run() {
  rm -rf "$WS_ABS/local" "$WS_ABS/local.properties"
  rm -f "$ROOT/state/lock.owner"
}
trap cleanup_run EXIT

GP="$GRADLE_USER_HOME/gradle.properties"
{
  printf 'org.gradle.daemon=true\n'
  printf 'org.gradle.workers.max=%s\n' "$WORKERS"
  printf 'org.gradle.caching=true\n'
  printf 'org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8\n'
  printf 'kotlin.daemon.jvmargs=-Xmx2g\n'
} > "$GP.tmp.$$"
mv "$GP.tmp.$$" "$GP"

RES="$ROOT/results/$RUNID"
LOG="$RES/build.log"
echo "[remote] 开始构建：assembleDebug$([ "$DO_TEST" = 1 ] && echo ' + 单元测试')（workers=$WORKERS）"
cd "$WS_ABS" || { echo "[remote] [x] 工作区不存在: $WS_ABS"; exit 7; }
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties

START=$(date +%s)
if [ "$WARM" = 1 ]; then
  GRADLE_CMD=("$GRADLE_BIN")
else
  GRADLE_CMD=("$GRADLE_BIN" --no-daemon)
fi
( exec 9>&-; "${GRADLE_CMD[@]}" "${GRADLE_ARGS[@]}" ) 2>&1 | tee "$LOG" 9>&-
rc=${PIPESTATUS[0]}
ELAPSED=$(( $(date +%s) - START ))

status=failed
if [ "$rc" -eq 0 ]; then
  mkdir -p "$RES/apk"
  if [ -f "$WS_ABS/$APK_REL" ]; then
    cp -p "$WS_ABS/$APK_REL" "$RES/apk/app-debug.apk"
    status=success
  else
    echo "[remote] [x] 构建成功但缺少产物: $APK_REL"
    rc=7
  fi
else
  echo "[remote] [x] Gradle 失败（rc=$rc）"
fi

# Keep the unit-test report even when the build/tests failed.
if [ -d "$WS_ABS/app/build/reports/tests/testDebugUnitTest" ]; then
  mkdir -p "$RES/test-report/html"
  cp -a "$WS_ABS/app/build/reports/tests/testDebugUnitTest/." "$RES/test-report/html/"
fi
if [ -d "$WS_ABS/app/build/test-results/testDebugUnitTest" ]; then
  mkdir -p "$RES/test-report/xml"
  cp -a "$WS_ABS/app/build/test-results/testDebugUnitTest/." "$RES/test-report/xml/"
fi

: > "$RES/manifest.tsv"
if [ "$status" = "success" ]; then
  apk="$RES/apk/app-debug.apk"
  sha=$(sha256sum "$apk" | awk '{print $1}')
  pkg=""; vc=""; vn=""; perms=""
  if command -v aapt2 >/dev/null 2>&1; then
    line=$(aapt2 dump badging "$apk" 2>/dev/null | head -1 || true)
    pkg=$(printf '%s\n' "$line" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1)
    vc=$(printf '%s\n' "$line" | sed -n 's/^.* versionCode='"'"'\([^'"'"']*\)'"'"'.*/\1/p' | head -1)
    vn=$(printf '%s\n' "$line" | sed -n 's/^.* versionName='"'"'\([^'"'"']*\)'"'"'.*/\1/p' | head -1)
    perms=$(aapt2 dump permissions "$apk" 2>/dev/null | sed -n 's/^uses-permission: name='"'"'\([^'"'"']*\)'"'"'.*/\1/p' | paste -sd, -)
  fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "app-debug.apk" "$sha" "$pkg" "$vc" "$vn" "$perms" >> "$RES/manifest.tsv"
fi

{
  printf 'status=%s\n' "$status"
  printf 'build_rc=%s\n' "$rc"
  printf 'runid=%s\n' "$RUNID"
  printf 'elapsed=%s\n' "$ELAPSED"
  printf 'finished=%s\n' "$(date -Is)"
} > "$RES/result.env.tmp"
mv "$RES/result.env.tmp" "$RES/result.env"

if [ "$status" = "success" ]; then
  echo "[remote] 构建成功（${ELAPSED}s），产物已放入 $RES/apk"
  exit 0
fi
echo "[remote] [x] 构建失败，日志: $LOG"
exit 6
REMOTE_RUN
  chmod 700 "$runner"
}

# ---------------------------------------------------------------- 取回与本地校验

verify_local_apk() {
  local apk="$1" want_sha="$2" want_pkg="$3" want_vc="$4" perms="$5"
  local got_sha
  got_sha=$(sha256sum "$apk" | awk '{print $1}')
  [ "$got_sha" = "$want_sha" ] || { error "APK sha256 不一致"; return 1; }
  unzip -t "$apk" >/dev/null 2>&1 || { error "APK 压缩结构损坏：$apk"; return 1; }

  if [ -n "$want_pkg" ] && [ -n "$EXPECTED_PACKAGE" ] && [ "$want_pkg" != "$EXPECTED_PACKAGE" ]; then
    error "包名不符合预期：$want_pkg（期望 $EXPECTED_PACKAGE）"; return 1
  fi
  if [ -n "$perms" ] && printf '%s' "$perms" | tr ',' '\n' | grep -qx 'android.permission.INTERNET'; then
    error "APK 声明了 INTERNET 权限，与离线设计不符"; return 1
  fi
  if [ -x "$LOCAL_AAPT2" ]; then
    local badging
    badging=$("$LOCAL_AAPT2" dump badging "$apk" 2>/dev/null | head -1 || true)
    printf '  package:  %s\n' "$(printf '%s\n' "$badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
    printf '  version:  %s (code %s)\n' \
      "$(printf '%s\n' "$badging" | sed -n 's/^.* versionName='"'"'\([^'"'"']*\)'"'"'.*/\1/p')" \
      "$(printf '%s\n' "$badging" | sed -n 's/^.* versionCode='"'"'\([^'"'"']*\)'"'"'.*/\1/p')"
    printf '  sdk:      %s\n' "$(printf '%s\n' "$badging" | sed -n 's/^sdkVersion:'"'"'\([^'"'"']*\)'"'"'.*/\1/p')"
  fi
  if [ -x "$LOCAL_APKSIGNER" ] && [ -x "$LOCAL_JDK/bin/java" ]; then
    PATH="$LOCAL_JDK/bin:$PATH" "$LOCAL_APKSIGNER" verify --print-certs "$apk" >/dev/null 2>&1 \
      || { error "APK 签名校验失败"; return 1; }
    printf '  signing:  debug signature OK\n'
  else
    warn "本机缺少 apksigner，跳过签名校验"
  fi
  return 0
}

fetch_results() {
  local remote_res="$1" runid="$2" tmp="$3"
  local log_local="$PROJECT_ROOT/tools/logs/build_remote_${runid}.log"
  mkdir -p "$(dirname "$log_local")"
  [ -f "$tmp/build.log" ] && cp -p "$tmp/build.log" "$log_local"

  local status
  status=$(sed -n 's/^status=//p' "$tmp/result.env" 2>/dev/null | head -1)

  if [ -d "$tmp/test-report" ]; then
    rm -rf "$PROJECT_ROOT/dist/test-report"
    mkdir -p "$PROJECT_ROOT/dist/test-report"
    cp -a "$tmp/test-report/." "$PROJECT_ROOT/dist/test-report/"
    log "单测报告：dist/test-report/html/index.html"
  fi

  if [ "$status" != "success" ]; then
    error "远端构建失败（status=${status:-unknown}）"
    if [ -f "$tmp/build.log" ]; then
      log "远端日志尾部："
      tail -n 80 "$tmp/build.log" | sed 's/^/          /' >&2
    fi
    warn "完整日志：$log_local"
    return 6
  fi

  [ -f "$tmp/manifest.tsv" ] || { error "远端缺少 manifest.tsv"; return 7; }
  local line base sha pkg vc vn perms
  line=$(head -1 "$tmp/manifest.tsv")
  IFS=$'\t' read -r base sha pkg vc vn perms <<< "$line"
  [ -f "$tmp/apk/app-debug.apk" ] || { error "取回目录缺少 app-debug.apk"; return 7; }

  mkdir -p "$PROJECT_ROOT/dist"
  local dest="$PROJECT_ROOT/dist/tiku-prompt-debug.apk"
  cp -p "$tmp/apk/app-debug.apk" "$dest"
  printf '%s  %s\n' "$sha" "tiku-prompt-debug.apk" > "$PROJECT_ROOT/dist/tiku-prompt-debug.apk.sha256"
  {
    printf 'package=%s\n' "$pkg"
    printf 'versionCode=%s\n' "$vc"
    printf 'versionName=%s\n' "$vn"
    printf 'permissions=%s\n' "$perms"
    printf 'sha256=%s\n' "$sha"
    printf 'runid=%s\n' "$runid"
  } > "$PROJECT_ROOT/dist/tiku-prompt-debug.metadata.txt"

  log "校验取回的 APK ..."
  verify_local_apk "$dest" "$sha" "$pkg" "$vc" "$perms" || return 7
  ok "APK：$dest"
  ok "sha256：$sha"
  printf '  权限：%s\n' "${perms:-（无）}"
  return 0
}

# ---------------------------------------------------------------- 主流程

local_preflight
remote_connect
remote_check || die 4 "远端工具链不完整；请检查 --toolchain-root / --gradle-bin"

# Maintenance flags are intentionally self-contained: run the action and stop.
# Combine maintenance and a build by invoking the script twice.
if [ "$DO_CHECK" = 1 ] || [ "$DO_PRUNE" = 1 ] || [ "$DO_RESET_WARM" = 1 ]; then
  [ "$DO_CHECK" = 1 ] && ok "远端检查通过"
  [ "$DO_RESET_WARM" = 1 ] && reset_warm
  [ "$DO_PRUNE" = 1 ] && prune_remote
  exit 0
fi

prepare_remote_dirs

RUNID="$(hostname -s 2>/dev/null | tr -cd 'A-Za-z0-9._-')-debug-$(date +%Y%m%d_%H%M%S)-$$"
[ -n "${RUNID%%-*}" ] || RUNID="client-debug-$(date +%Y%m%d_%H%M%S)-$$"

if [ "$WARM" = 1 ]; then
  WS_REL="$WARM_WS_REL"
else
  WS_REL="workspaces/$RUNID/tiku_project"
fi
WS_ABS="$REMOTE_ROOT_ABS/$WS_REL"
RES_REL="results/$RUNID"

sync_source "$WS_ABS"

job_tmp=$(mktemp) || die 3 "mktemp 失败"
run_tmp=$(mktemp) || die 3 "mktemp 失败"
trap 'rm -f "$job_tmp" "$run_tmp"' EXIT
write_remote_job "$job_tmp" "$WS_REL" "$RUNID"
write_remote_runner "$run_tmp"
RUN_DIR="$REMOTE_ROOT_ABS/workspaces/$RUNID"
ssh "${SSH_OPTS[@]}" "$REMOTE_SSH" "mkdir -p '$RUN_DIR'" || die 4 "创建远端运行目录失败"
scp -q "${SSH_OPTS[@]}" "$run_tmp" "$REMOTE_SSH:$RUN_DIR/run.sh" || die 4 "上传 runner 失败"
scp -q "${SSH_OPTS[@]}" "$job_tmp" "$REMOTE_SSH:$RUN_DIR/job.env" || die 4 "上传 job.env 失败"
ssh "${SSH_OPTS[@]}" "$REMOTE_SSH" "chmod 700 '$RUN_DIR/run.sh' '$RUN_DIR/job.env'" \
  || die 4 "设置远端 runner 权限失败"
rm -f "$job_tmp" "$run_tmp"

log "远端日志：$REMOTE_SSH:$REMOTE_ROOT_ABS/$RES_REL/build.log"
log "===== 在远端构建机构建 [debug $([ "$DO_TEST" = 1 ] && echo '+ test')] ====="

rc=0
ssh "${SSH_OPTS[@]}" "$REMOTE_SSH" "bash '$RUN_DIR/run.sh'" || rc=$?

if [ "$rc" = 75 ]; then
  error "构建锁等待超时（${WAIT}s）；另一台机器正在构建，可加大 --wait"
  [ "$KEEP" = 0 ] && ssh "${SSH_OPTS[@]}" "$REMOTE_SSH" "rm -rf '$RUN_DIR'" >/dev/null 2>&1 || true
  exit 75
fi

tmp=$(mktemp -d) || die 3 "mktemp 失败"
if ! rsync -a "$REMOTE_SSH:$REMOTE_ROOT_ABS/$RES_REL/" "$tmp/" 2>/dev/null; then
  error "取回远端产物失败（$REMOTE_ROOT_ABS/$RES_REL）"
  rm -rf "$tmp"
  [ "$KEEP" = 0 ] && ssh "${SSH_OPTS[@]}" "$REMOTE_SSH" \
    "rm -rf '$RUN_DIR' '$REMOTE_ROOT_ABS/$RES_REL'" >/dev/null 2>&1 || true
  exit 7
fi

fetch_rc=0
fetch_results "$RES_REL" "$RUNID" "$tmp" || fetch_rc=$?
rm -rf "$tmp"

if [ "$KEEP" = 1 ]; then
  warn "已保留远端运行目录：$REMOTE_SSH:$RUN_DIR"
  [ "$WARM" = 1 ] && log "warm 工作区保留：$REMOTE_SSH:$WS_ABS"
else
  ssh "${SSH_OPTS[@]}" "$REMOTE_SSH" \
    "rm -rf '$RUN_DIR' '$REMOTE_ROOT_ABS/$RES_REL'" >/dev/null 2>&1 \
    || warn "远端清理失败（$RUN_DIR），可稍后 --prune"
fi
if [ "$WARM" = 1 ]; then
  log "warm 工作区保留：$REMOTE_SSH:$WS_ABS（下次增量编译；--reset-warm 可重置）"
fi

exit "$fetch_rc"