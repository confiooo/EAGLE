#!/usr/bin/env bash
# Remove Eagle systemd unit (user or system). Does not delete install files.
# Usage:
#   ./scripts/uninstall-systemd.sh user
#   sudo ./scripts/uninstall-systemd.sh system
set -euo pipefail

MODE="${1:-}"
if [[ "$MODE" != "user" && "$MODE" != "system" ]]; then
  echo "Usage: $0 user|system" >&2
  exit 1
fi

if [[ "$MODE" == "user" ]]; then
  systemctl --user disable --now eagle.service 2>/dev/null || true
  UNIT="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user/eagle.service"
  rm -f "$UNIT"
  systemctl --user daemon-reload
  echo "Removed user unit: $UNIT"
elif [[ "$MODE" == "system" ]]; then
  if [[ $EUID -ne 0 ]]; then
    echo "error: system mode requires sudo" >&2
    exit 1
  fi
  systemctl disable --now eagle.service 2>/dev/null || true
  rm -f /etc/systemd/system/eagle.service
  systemctl daemon-reload
  echo "Removed /etc/systemd/system/eagle.service"
fi
