#!/usr/bin/env bash
# Build Eagle and optionally install files + systemd (user or system).
# Usage:
#   ./scripts/setup-linux.sh                    # build only
#   ./scripts/setup-linux.sh --install DIR     # copy jar + env template into DIR
#   ./scripts/setup-linux.sh --install DIR --systemd user
#   ./scripts/setup-linux.sh --install DIR --systemd system
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$PROJECT_ROOT"

MIN_JAVA_MAJOR=17
INSTALL_DIR=""
SYSTEMD_MODE="" # user | system
SERVICE_USER="${EAGLE_SERVICE_USER:-}"

usage() {
  cat <<'HELP'
Build Eagle and optionally install files + systemd (user or system).

Usage:
  ./scripts/setup-linux.sh
  ./scripts/setup-linux.sh --install DIR
  ./scripts/setup-linux.sh --install DIR --systemd user
  sudo EAGLE_SERVICE_USER=myuser ./scripts/setup-linux.sh --install DIR --systemd system

Environment:
  EAGLE_SERVICE_USER   Unix account for system service (default: SUDO_USER or root)
HELP
  exit "${1:-0}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage ;;
    --install)
      INSTALL_DIR=$(cd "$(dirname "$2")" && pwd)/$(basename "$2")
      shift 2
      ;;
    --systemd)
      SYSTEMD_MODE="$2"
      shift 2
      ;;
    *) echo "Unknown option: $1" >&2; usage 1 ;;
  esac
done

if [[ -n "$SYSTEMD_MODE" && -z "$INSTALL_DIR" ]]; then
  echo "error: --systemd requires --install DIR (service needs a fixed install path)" >&2
  exit 1
fi

if [[ -n "$SYSTEMD_MODE" && "$SYSTEMD_MODE" != "user" && "$SYSTEMD_MODE" != "system" ]]; then
  echo "error: --systemd must be 'user' or 'system'" >&2
  exit 1
fi

java_major() {
  local line major
  line=$(java -version 2>&1 | head -n1)
  if [[ "$line" =~ version\ \"1\.([0-9]+) ]]; then
    echo "${BASH_REMATCH[1]}"
    return
  fi
  if [[ "$line" =~ version\ \"([0-9]+) ]]; then
    echo "${BASH_REMATCH[1]}"
    return
  fi
  echo 0
}

require_java() {
  if ! command -v java &>/dev/null; then
    echo "Java not found. Install JDK ${MIN_JAVA_MAJOR}+, for example:" >&2
    echo "  Arch:        sudo pacman -S jdk-openjdk" >&2
    echo "  Debian/Ubuntu: sudo apt install openjdk-17-jdk" >&2
    echo "  Fedora:      sudo dnf install java-17-openjdk-devel" >&2
    exit 1
  fi
  local major
  major=$(java_major)
  if (( major < MIN_JAVA_MAJOR && major != 0 )); then
    echo "error: need Java ${MIN_JAVA_MAJOR}+ (found major version ${major})" >&2
    exit 1
  fi
}

require_java

if [[ ! -f ./gradlew ]]; then
  echo "error: gradlew not found in $PROJECT_ROOT" >&2
  exit 1
fi
GRADLE=(./gradlew)
[[ -x ./gradlew ]] || GRADLE=(bash ./gradlew)

echo "==> Building jar..."
"${GRADLE[@]}" --no-daemon jar

JAR=$(ls -1 "$PROJECT_ROOT/build/libs"/eagle-*.jar 2>/dev/null | grep -v -- '-plain' | head -n1)
if [[ -z "$JAR" || ! -f "$JAR" ]]; then
  echo "error: could not find build/libs/eagle-*.jar" >&2
  exit 1
fi

echo "==> Built: $JAR"

if [[ -z "$INSTALL_DIR" ]]; then
  echo "Done. Run from this directory (needs config.json + .env):"
  echo "  ./gradlew run"
  echo "  # or: java -jar \"$JAR\""
  exit 0
fi

echo "==> Installing to $INSTALL_DIR ..."
mkdir -p "$INSTALL_DIR"
cp -f "$JAR" "$INSTALL_DIR/eagle.jar"
if [[ ! -f "$INSTALL_DIR/config.json" ]]; then
  cp -f "$PROJECT_ROOT/config.example.json" "$INSTALL_DIR/config.json"
  echo "  copied config.json from config.example.json (edit chat ids and language per instance)"
else
  echo "  kept existing config.json"
fi
if [[ ! -f "$INSTALL_DIR/.env" ]]; then
  cp -f "$PROJECT_ROOT/.env.example" "$INSTALL_DIR/.env"
  echo "  copied .env from .env.example — set TELEGRAM_BOT_TOKEN"
else
  echo "  kept existing .env"
fi

JAVA_BIN=$(command -v java)

write_unit() {
  local out_path=$1
  local extra=$2
  umask 077
  cat >"$out_path" <<EOF
[Unit]
Description=Eagle Binance EMA Telegram bot
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=${INSTALL_DIR}
ExecStart=${JAVA_BIN} -jar ${INSTALL_DIR}/eagle.jar
Restart=on-failure
RestartSec=10

${extra}
[Install]
EOF
}

if [[ "$SYSTEMD_MODE" == "user" ]]; then
  UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
  mkdir -p "$UNIT_DIR"
  write_unit "$UNIT_DIR/eagle.service" ""
  echo "WantedBy=default.target" >>"$UNIT_DIR/eagle.service"
  chmod 0644 "$UNIT_DIR/eagle.service"
  echo "==> User systemd unit: $UNIT_DIR/eagle.service"
  systemctl --user daemon-reload
  systemctl --user enable --now eagle.service
  echo "Enabled and started (user). Check: systemctl --user status eagle"
  echo "Tip: for boot without login session: loginctl enable-linger \"$USER\""
elif [[ "$SYSTEMD_MODE" == "system" ]]; then
  if [[ $EUID -ne 0 ]]; then
    echo "error: --systemd system must be run with sudo" >&2
    exit 1
  fi
  if [[ -z "$SERVICE_USER" ]]; then
    SERVICE_USER="${SUDO_USER:-root}"
  fi
  write_unit "/etc/systemd/system/eagle.service" "User=${SERVICE_USER}
Group=${SERVICE_USER}"
  echo "WantedBy=multi-user.target" >>/etc/systemd/system/eagle.service
  chmod 0644 /etc/systemd/system/eagle.service
  chown -R "${SERVICE_USER}:" "$INSTALL_DIR"
  echo "==> System unit: /etc/systemd/system/eagle.service (User=${SERVICE_USER})"
  systemctl daemon-reload
  systemctl enable --now eagle.service
  echo "Enabled and started. Check: systemctl status eagle"
else
  echo "Install complete. Run manually:"
  echo "  cd \"$INSTALL_DIR\" && $JAVA_BIN -jar eagle.jar"
  echo "Or add systemd: re-run with --install \"$INSTALL_DIR\" --systemd user|system"
fi
