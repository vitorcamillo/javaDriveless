#!/usr/bin/env bash

set -euo pipefail

PROGRAM=${0##*/}
MAX_DIMENSION=32768
MAX_TIMEOUT_SECONDS=120

# These are output handshake values, never input overrides. Discard inherited
# values until this process has provisioned and verified the native surface.
unset JAVA_DRIVERLESS_NATIVE_SCREEN_PROVISIONED \
  JAVA_DRIVERLESS_NATIVE_SCREEN_WIDTH \
  JAVA_DRIVERLESS_NATIVE_SCREEN_HEIGHT \
  JAVA_DRIVERLESS_NATIVE_SCREEN_AVAIL_WIDTH \
  JAVA_DRIVERLESS_NATIVE_SCREEN_AVAIL_HEIGHT \
  JAVA_DRIVERLESS_NATIVE_SCREEN_COLOR_DEPTH \
  JAVA_DRIVERLESS_NATIVE_SCREEN_PIXEL_DEPTH \
  JAVA_DRIVERLESS_NATIVE_SCREEN_DPR

usage() {
  cat <<EOF
Usage: $PROGRAM [options] -- command [args...]

Starts an isolated Xvfb server, publishes a native X11 _NET_WORKAREA, then
runs the command with DISPLAY pointing at that server.

Options (environment fallback):
  --display auto|:N       JAVA_DRIVERLESS_XVFB_DISPLAY (default: auto)
  --screen-width N        JAVA_DRIVERLESS_SCREEN_WIDTH (default: 1365)
  --screen-height N       JAVA_DRIVERLESS_SCREEN_HEIGHT (default: 900)
  --avail-width N         JAVA_DRIVERLESS_SCREEN_AVAIL_WIDTH (default: screen width)
  --avail-height N        JAVA_DRIVERLESS_SCREEN_AVAIL_HEIGHT (default: screen height - 40)
  --depth N               JAVA_DRIVERLESS_XVFB_DEPTH (default: 24)
  --startup-timeout N     JAVA_DRIVERLESS_XVFB_STARTUP_TIMEOUT_SECONDS (default: 10)
  -h, --help              Show this help
EOF
}

die() {
  printf '%s: %s\n' "$PROGRAM" "$*" >&2
  exit 2
}

require_value() {
  local option=$1
  local count=$2
  ((count >= 2)) || die "$option requires a value"
}

normalize_uint() {
  local name=$1
  local value=$2
  local minimum=$3
  local maximum=$4
  [[ "$value" =~ ^[0-9]+$ ]] || die "$name must be an integer"
  value=$((10#$value))
  ((value >= minimum && value <= maximum)) \
    || die "$name must be between $minimum and $maximum"
  printf '%s' "$value"
}

append_remove_default_argument() {
  local argument=$1
  local current=${JAVA_DRIVERLESS_REMOVE_DEFAULT_ARGS:-}
  if [[ ",$current," != *",$argument,"* ]]; then
    current=${current:+"$current,"}$argument
  fi
  export JAVA_DRIVERLESS_REMOVE_DEFAULT_ARGS=$current
}

append_window_size_argument() {
  local argument=$1
  local current=${JAVA_DRIVERLESS_EXTRA_ARGS:-}
  local normalized=${current//;/$'\n'}
  local item
  local found=false
  while IFS= read -r item; do
    item=${item#"${item%%[![:space:]]*}"}
    item=${item%"${item##*[![:space:]]}"}
    case "$item" in
      "$argument")
        found=true
        ;;
      --window-size=*)
        die "JAVA_DRIVERLESS_EXTRA_ARGS contains a conflicting --window-size"
        ;;
      --headless|--headless=*)
        die "JAVA_DRIVERLESS_EXTRA_ARGS cannot enable headless mode in the visible launcher"
        ;;
    esac
  done <<<"$normalized"
  if [[ "$found" != "true" ]]; then
    current=${current:+"$current;"}$argument
  fi
  export JAVA_DRIVERLESS_EXTRA_ARGS=$current
}

display=${JAVA_DRIVERLESS_XVFB_DISPLAY:-auto}
screen_width=${JAVA_DRIVERLESS_SCREEN_WIDTH:-1365}
screen_height=${JAVA_DRIVERLESS_SCREEN_HEIGHT:-900}
avail_width=${JAVA_DRIVERLESS_SCREEN_AVAIL_WIDTH:-}
avail_height=${JAVA_DRIVERLESS_SCREEN_AVAIL_HEIGHT:-}
depth=${JAVA_DRIVERLESS_XVFB_DEPTH:-24}
startup_timeout=${JAVA_DRIVERLESS_XVFB_STARTUP_TIMEOUT_SECONDS:-10}

while (($#)); do
  case "$1" in
    --display)
      require_value "$1" "$#"
      display=$2
      shift 2
      ;;
    --screen-width)
      require_value "$1" "$#"
      screen_width=$2
      shift 2
      ;;
    --screen-height)
      require_value "$1" "$#"
      screen_height=$2
      shift 2
      ;;
    --avail-width)
      require_value "$1" "$#"
      avail_width=$2
      shift 2
      ;;
    --avail-height)
      require_value "$1" "$#"
      avail_height=$2
      shift 2
      ;;
    --depth)
      require_value "$1" "$#"
      depth=$2
      shift 2
      ;;
    --startup-timeout)
      require_value "$1" "$#"
      startup_timeout=$2
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      break
      ;;
    --*)
      die "unknown option: $1"
      ;;
    *)
      break
      ;;
  esac
done

(($# > 0)) || die "a command is required"

screen_width=$(normalize_uint "screen width" "$screen_width" 1 "$MAX_DIMENSION")
screen_height=$(normalize_uint "screen height" "$screen_height" 41 "$MAX_DIMENSION")
avail_width=${avail_width:-$screen_width}
avail_height=${avail_height:-$((screen_height - 40))}
avail_width=$(normalize_uint "available width" "$avail_width" 1 "$screen_width")
avail_height=$(normalize_uint "available height" "$avail_height" 9 "$screen_height")
depth=$(normalize_uint "screen depth" "$depth" 8 32)
startup_timeout=$(normalize_uint "startup timeout" "$startup_timeout" 1 "$MAX_TIMEOUT_SECONDS")

if [[ "$display" != "auto" && ! "$display" =~ ^:[0-9]+$ ]]; then
  die "display must be auto or :N"
fi

for required_command in Xvfb xprop setsid stat; do
  command -v "$required_command" >/dev/null 2>&1 \
    || die "required command not found: $required_command"
done

# Xvfb refuses to create this well-known socket directory when running as a
# non-root container user. Create it explicitly, but never follow or replace a
# pre-existing non-directory path.
x11_socket_dir=/tmp/.X11-unix
[[ ! -L "$x11_socket_dir" ]] || die "$x11_socket_dir must not be a symbolic link"
if [[ ! -e "$x11_socket_dir" ]]; then
  ((EUID == 0)) \
    || die "$x11_socket_dir must be pre-created as root with mode 1777"
  mkdir -m 1777 -- "$x11_socket_dir"
fi
[[ -d "$x11_socket_dir" ]] || die "$x11_socket_dir must be a directory"
[[ "$(stat -c %u "$x11_socket_dir")" == "0" ]] \
  || die "$x11_socket_dir must be owned by root"
[[ "$(stat -c %a "$x11_socket_dir")" == "1777" ]] \
  || die "$x11_socket_dir must have mode 1777"

runtime_dir=$(mktemp -d "${TMPDIR:-/tmp}/java-driverless-xvfb.XXXXXX")
xvfb_log="$runtime_dir/xvfb.log"
display_number_file="$runtime_dir/display-number"
xvfb_pid=""
child_pid=""

terminate_owned_process() {
  local pid=$1
  [[ -n "$pid" ]] || return 0
  kill -0 "$pid" 2>/dev/null || return 0
  kill -TERM "$pid" 2>/dev/null || true
  local attempt
  for attempt in $(seq 1 20); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.05
  done
  if kill -0 "$pid" 2>/dev/null; then
    kill -KILL "$pid" 2>/dev/null || true
  fi
  wait "$pid" 2>/dev/null || true
}

terminate_owned_process_group() {
  local leader_pid=$1
  [[ -n "$leader_pid" ]] || return 0
  kill -0 -- "-$leader_pid" 2>/dev/null || return 0
  kill -TERM -- "-$leader_pid" 2>/dev/null || true
  local attempt
  for attempt in $(seq 1 20); do
    kill -0 -- "-$leader_pid" 2>/dev/null || break
    sleep 0.05
  done
  if kill -0 -- "-$leader_pid" 2>/dev/null; then
    kill -KILL -- "-$leader_pid" 2>/dev/null || true
  fi
  wait "$leader_pid" 2>/dev/null || true
}

cleanup() {
  local status=$?
  trap - EXIT INT TERM HUP
  terminate_owned_process_group "$child_pid"
  child_pid=""
  terminate_owned_process "$xvfb_pid"
  xvfb_pid=""
  rm -rf -- "$runtime_dir"
  exit "$status"
}

handle_signal() {
  local signal=$1
  local status=$2
  if [[ -n "$child_pid" ]]; then
    kill -"$signal" -- "-$child_pid" 2>/dev/null || true
  fi
  exit "$status"
}

trap cleanup EXIT
trap 'handle_signal INT 130' INT
trap 'handle_signal TERM 143' TERM
trap 'handle_signal HUP 129' HUP

screen_spec="${screen_width}x${screen_height}x${depth}"
if [[ "$display" == "auto" ]]; then
  : >"$display_number_file"
  Xvfb -displayfd 3 -screen 0 "$screen_spec" -nolisten tcp -noreset \
    3>"$display_number_file" >"$xvfb_log" 2>&1 &
else
  ! DISPLAY="$display" xprop -root >/dev/null 2>&1 || die "display $display is already in use"
  Xvfb "$display" -screen 0 "$screen_spec" -nolisten tcp -noreset \
    >"$xvfb_log" 2>&1 &
fi
xvfb_pid=$!

deadline=$((SECONDS + startup_timeout))
while :; do
  if ! kill -0 "$xvfb_pid" 2>/dev/null; then
    printf '%s: Xvfb exited before becoming ready\n' "$PROGRAM" >&2
    tail -n 20 "$xvfb_log" >&2 || true
    exit 3
  fi

  if [[ "$display" == "auto" && -s "$display_number_file" ]]; then
    display_number=$(tr -d '[:space:]' <"$display_number_file")
    [[ "$display_number" =~ ^[0-9]+$ ]] \
      || die "Xvfb returned an invalid display number"
    display=:$display_number
  fi

  if [[ "$display" != "auto" ]] && DISPLAY="$display" xprop -root >/dev/null 2>&1; then
    break
  fi

  if ((SECONDS >= deadline)); then
    printf '%s: timed out waiting for Xvfb readiness\n' "$PROGRAM" >&2
    tail -n 20 "$xvfb_log" >&2 || true
    exit 3
  fi
  sleep 0.1
done

workarea="0, 0, $avail_width, $avail_height"
# On a pristine Xvfb server the EWMH atom does not exist yet. Querying it with
# an explicit format interns the atom; -noreset keeps it alive between xprop
# clients so the following set is effective before Chrome connects.
DISPLAY="$display" xprop -root -f _NET_WORKAREA 32c _NET_WORKAREA >/dev/null
DISPLAY="$display" xprop -root -f _NET_WORKAREA 32c \
  -set _NET_WORKAREA "$workarea" >/dev/null
workarea_readback=$(DISPLAY="$display" xprop -root _NET_WORKAREA)
if [[ ! "$workarea_readback" =~ =[[:space:]]*0,[[:space:]]*0,[[:space:]]*([0-9]+),[[:space:]]*([0-9]+) ]] \
    || [[ "${BASH_REMATCH[1]:-}" != "$avail_width" ]] \
    || [[ "${BASH_REMATCH[2]:-}" != "$avail_height" ]]; then
  die "_NET_WORKAREA readback does not match the requested dimensions"
fi

export DISPLAY=$display
export JAVA_DRIVERLESS_HEADLESS=0
export JAVA_DRIVERLESS_SCREEN_WIDTH=$screen_width
export JAVA_DRIVERLESS_SCREEN_HEIGHT=$screen_height
export JAVA_DRIVERLESS_SCREEN_AVAIL_WIDTH=$avail_width
export JAVA_DRIVERLESS_SCREEN_AVAIL_HEIGHT=$avail_height
export JAVA_DRIVERLESS_SCREEN_WORKAREA=off
export JAVA_DRIVERLESS_NATIVE_SCREEN_PROVISIONED=1
export JAVA_DRIVERLESS_NATIVE_SCREEN_WIDTH=$screen_width
export JAVA_DRIVERLESS_NATIVE_SCREEN_HEIGHT=$screen_height
export JAVA_DRIVERLESS_NATIVE_SCREEN_AVAIL_WIDTH=$avail_width
export JAVA_DRIVERLESS_NATIVE_SCREEN_AVAIL_HEIGHT=$avail_height
export JAVA_DRIVERLESS_NATIVE_SCREEN_COLOR_DEPTH=$depth
export JAVA_DRIVERLESS_NATIVE_SCREEN_PIXEL_DEPTH=$depth
export JAVA_DRIVERLESS_NATIVE_SCREEN_DPR=1

append_remove_default_argument "--start-maximized"
window_height=$((avail_height - 8))
append_window_size_argument "--window-size=$screen_width,$window_height"

printf '[java-driverless-xvfb] display=%s screen=%s workarea=%s\n' \
  "$DISPLAY" "$screen_spec" "$workarea" >&2

setsid -- "$@" &
child_pid=$!
set +e
wait "$child_pid"
status=$?
set -e
exit "$status"
