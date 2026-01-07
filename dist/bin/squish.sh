#!/bin/sh
#
# Squish - PDF Compression Engine
# Startup script for Linux/macOS (POSIX compatible)
#
# Usage: ./squish.sh [start|stop|restart|status]
#

APP_NAME="Squish"
JAR_FILE="squish.jar"
PID_FILE="squish.pid"
LOG_FILE="squish.log"

# Java options (can be overridden via environment)
if [ -z "$JAVA_OPTS" ]; then
    JAVA_OPTS="-Xms256m -Xmx2g"
fi

# Profile (dev, test, prod)
if [ -z "$SPRING_PROFILES_ACTIVE" ]; then
    SPRING_PROFILES_ACTIVE="prod"
fi
PROFILE="$SPRING_PROFILES_ACTIVE"

# Get script directory (POSIX compatible)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_HOME="$(dirname "$SCRIPT_DIR")"

# Check Java
check_java() {
    if ! which java > /dev/null 2>&1; then
        echo "ERROR: Java not found. Please install Java 22 or higher."
        exit 1
    fi
    
    JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VERSION" -lt 22 ] 2>/dev/null; then
        echo "ERROR: Java 22 or higher required. Found version: $JAVA_VERSION"
        exit 1
    fi
}

start() {
    if [ -f "$APP_HOME/$PID_FILE" ]; then
        PID=$(cat "$APP_HOME/$PID_FILE")
        if ps -p "$PID" > /dev/null 2>&1; then
            echo "$APP_NAME is already running (PID: $PID)"
            exit 1
        fi
    fi
    
    check_java
    
    echo "Starting $APP_NAME..."
    cd "$APP_HOME" || exit 1
    
    nohup java $JAVA_OPTS -jar "$JAR_FILE" \
        --spring.profiles.active="$PROFILE" \
        > "$LOG_FILE" 2>&1 &
    
    echo $! > "$PID_FILE"
    sleep 2
    
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        if ps -p "$PID" > /dev/null 2>&1; then
            echo "$APP_NAME started (PID: $PID)"
            echo "Dashboard: http://localhost:8080/"
            echo "Log file: $APP_HOME/$LOG_FILE"
            return 0
        fi
    fi
    echo "ERROR: Failed to start $APP_NAME"
    exit 1
}

stop() {
    if [ ! -f "$APP_HOME/$PID_FILE" ]; then
        echo "$APP_NAME is not running"
        return 0
    fi
    
    PID=$(cat "$APP_HOME/$PID_FILE")
    
    if ! ps -p "$PID" > /dev/null 2>&1; then
        echo "$APP_NAME is not running (stale PID file)"
        rm -f "$APP_HOME/$PID_FILE"
        return 0
    fi
    
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

status() {
    if [ -f "$APP_HOME/$PID_FILE" ]; then
        PID=$(cat "$APP_HOME/$PID_FILE")
        if ps -p "$PID" > /dev/null 2>&1; then
            echo "$APP_NAME is running (PID: $PID)"
            echo "Dashboard: http://localhost:8080/"
            exit 0
        fi
    fi
    echo "$APP_NAME is not running"
    exit 1
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
