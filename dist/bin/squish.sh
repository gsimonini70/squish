#!/bin/sh
#
# Squish - PDF Compression Engine
# Startup script for Linux/macOS (POSIX compatible)
#
# Usage: ./squish.sh [start|stop|restart|status]
#
# This is the MANUAL control script (and what /usr/local/bin/squish points at).
# It backgrounds bin/squish-run.sh, which is the single launcher shared with
# systemd and the SysV init script.
#
# NOTE: like the SysV init script, this script does NOT respawn a crashed JVM.
# Squish exits non-zero on an unrecoverable failure (a fatal Error in watchdog
# mode, a failed pipeline stage) and expects a supervisor to bring it back. For
# an unattended production install, use systemd (dist/service/squish.service).
# 'status' reports a crash honestly - see the exit codes below.
#

APP_NAME="Squish"
PID_FILE="squish.pid"
LOG_FILE="squish.log"

# Get script directory (POSIX compatible)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_HOME="$(dirname "$SCRIPT_DIR")"
LAUNCHER="$SCRIPT_DIR/squish-run.sh"

# Load environment file if exists.
# `set -a` exports everything the file defines, so it works whether or not the
# lines carry an explicit `export` keyword.
ENV_FILE="$APP_HOME/config/squish.env"
if [ -f "$ENV_FILE" ]; then
    set -a
    # shellcheck disable=SC1090
    . "$ENV_FILE"
    set +a
fi

DASHBOARD_SCHEME="http"
if [ "$HTTP_SSL_ENABLED" = "true" ]; then
    DASHBOARD_SCHEME="https"
fi
DASHBOARD_URL="$DASHBOARD_SCHEME://localhost:${HTTP_PORT:-8080}/"

# Returns 0 if running, 1 if dead but PID file exists (crashed), 3 if not running.
probe() {
    if [ ! -f "$APP_HOME/$PID_FILE" ]; then
        return 3
    fi
    PID=$(cat "$APP_HOME/$PID_FILE" 2>/dev/null)
    if [ -z "$PID" ]; then
        return 1
    fi
    if ps -p "$PID" > /dev/null 2>&1; then
        return 0
    fi
    return 1
}

start() {
    probe
    case $? in
        0)
            echo "$APP_NAME is already running (PID: $PID)"
            exit 1
            ;;
        1)
            echo "$APP_NAME left a stale PID file - it crashed or was killed. Restarting."
            rm -f "$APP_HOME/$PID_FILE"
            ;;
    esac

    if [ ! -x "$LAUNCHER" ]; then
        echo "ERROR: launcher not found or not executable: $LAUNCHER"
        exit 1
    fi

    echo "Starting $APP_NAME..."
    cd "$APP_HOME" || exit 1

    # squish-run.sh validates Java/config, creates logs/ and reports/, then
    # exec()s the JVM - so $! below is the PID of the JVM itself.
    nohup "$LAUNCHER" > "$LOG_FILE" 2>&1 &
    echo $! > "$PID_FILE"

    sleep 3

    if probe; then
        echo "$APP_NAME started (PID: $PID)"
        echo "Dashboard: $DASHBOARD_URL"
        echo "Log file: $APP_HOME/$LOG_FILE"
        return 0
    fi

    echo "ERROR: Failed to start $APP_NAME - see $APP_HOME/$LOG_FILE"
    rm -f "$APP_HOME/$PID_FILE"
    exit 1
}

stop() {
    probe
    case $? in
        3)
            echo "$APP_NAME is not running"
            return 0
            ;;
        1)
            echo "$APP_NAME is not running (stale PID file removed)"
            rm -f "$APP_HOME/$PID_FILE"
            return 0
            ;;
    esac

    echo "Stopping $APP_NAME (PID: $PID)..."
    kill "$PID"

    # Wait for graceful shutdown (POSIX compatible loop)
    COUNT=0
    while [ $COUNT -lt 30 ]; do
        if ! ps -p "$PID" > /dev/null 2>&1; then
            break
        fi
        sleep 1
        COUNT=$((COUNT + 1))
    done

    # Force kill if still running
    if ps -p "$PID" > /dev/null 2>&1; then
        echo "Force killing..."
        kill -9 "$PID"
    fi

    rm -f "$APP_HOME/$PID_FILE"
    echo "$APP_NAME stopped"
}

# LSB exit codes: 0 running, 1 dead but PID file exists (crashed), 3 not running.
status() {
    probe
    case $? in
        0)
            echo "$APP_NAME is running (PID: $PID)"
            echo "Dashboard: $DASHBOARD_URL"
            exit 0
            ;;
        1)
            echo "$APP_NAME is DEAD but $APP_HOME/$PID_FILE exists - it crashed and"
            echo "  nothing restarted it. Look for 'FATAL' in $APP_HOME/$LOG_FILE."
            exit 1
            ;;
        *)
            echo "$APP_NAME is not running"
            exit 3
            ;;
    esac
}

case "$1" in
    start)
        start
        ;;
    stop)
        stop
        ;;
    restart)
        stop
        sleep 2
        start
        ;;
    status)
        status
        ;;
    *)
        echo "Usage: $0 {start|stop|restart|status}"
        exit 1
        ;;
esac
