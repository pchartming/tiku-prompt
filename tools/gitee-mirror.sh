#!/usr/bin/env bash
# Gitee release mirror helper. GitHub remains the source of truth.
#
#   tools/gitee-mirror.sh check --tag vX.Y.Z
#   tools/gitee-mirror.sh status [--tag vX.Y.Z]
#   tools/gitee-mirror.sh upload --tag vX.Y.Z --expected-commit <sha> [--dry-run]
#
# `check` needs no credentials. `status` and `upload` need TIKU_GITEE_TOKEN
# owned by the pchartming Gitee account. The token is never printed.
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 tools/gitee_mirror.py "$@"