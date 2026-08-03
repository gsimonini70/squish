#!/bin/sh
#
# Squish - Foreground launcher (POSIX sh)
# ============================================
# This is the ONE place that turns config/squish.env into a running JVM.
# It is used by:
#   - systemd      (ExecStart=/opt/squish/bin/squish-run.sh)
#   - the SysV init script  (nohup'd, as the service user)
#   - bin/squish.sh start   (nohup'd, manual/foreground use)
#
# It runs the JVM in the FOREGROUND and exec()s it, so:
#   - the PID of this script IS the PID of the JVM (exec replaces the shell);
#   - a supervisor sees the real process and its real exit code.
# That matters: WatchdogService now exits 1 on a fatal Error (e.g. OutOfMemoryError)
# instead of turning into a zombie that serves the dashboard but processes nothing.
# The recovery from that exit(1) is *the supervisor restarting us*. Do not
# background the JVM here, and do not wrap it in a shell that swallows its status.
#
# Credentials are passed to the JVM through the ENVIRONMENT, never on the command
# line, so they do not show up in `ps aux`. application-prod.yml resolves every
# ${VAR} placeholder from that environment.
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_HOME="${SQUISH_HOME:-$(dirname "$SCRIPT_DIR")}"
JAR_FILE="$APP_HOME/squish.jar"
ENV_FILE="$APP_HOME/config/squish.env"

cd "$APP_HOME" || exit 1

# ---------------------------------------------------------------
# 1. Load the environment file
# ---------------------------------------------------------------
# `set -a` exports every variable the file defines, so squish.env works whether
# or not its lines carry an explicit `export` keyword.
if [ -f "$ENV_FILE" ]; then
    set -a
    # shellcheck disable=SC1090
    . "$ENV_FILE"
    set +a
else
    echo "ERROR: environment file not found: $ENV_FILE" >&2
    echo "       Copy config/squish.env.template to config/squish.env and edit it." >&2
    exit 1
fi

# ---------------------------------------------------------------
# 2. Fail fast on missing essentials (a clear message beats a stack trace)
# ---------------------------------------------------------------
if [ -z "$DB_URL" ]; then
    echo "ERROR: DB_URL is not set in $ENV_FILE" >&2
    exit 1
fi

if [ -z "$JAVA_HOME" ]; then
    JAVA_CMD="$(command -v java 2>/dev/null || true)"
    if [ -z "$JAVA_CMD" ]; then
        echo "ERROR: JAVA_HOME is not set in $ENV_FILE and 'java' is not on PATH" >&2
        exit 1
    fi
else
    JAVA_CMD="$JAVA_HOME/bin/java"
    if [ ! -x "$JAVA_CMD" ]; then
        echo "ERROR: Java not found at $JAVA_CMD (check JAVA_HOME in $ENV_FILE)" >&2
        exit 1
    fi
fi

if [ ! -f "$JAR_FILE" ]; then
    echo "ERROR: $JAR_FILE not found" >&2
    exit 1
fi

# ---------------------------------------------------------------
# 3. Writable runtime directories
# ---------------------------------------------------------------
# squish.report.directory defaults to 'reports', RELATIVE to the working
# directory. Under systemd's ProtectSystem=strict this path must also appear in
# ReadWritePaths= (see dist/service/squish.service) or the report write fails at
# the end of a run, long after anyone is watching.
REPORT_DIR="${REPORT_DIRECTORY:-reports}"
mkdir -p "$APP_HOME/logs" "$REPORT_DIR" 2>/dev/null || true

if [ ! -w "$APP_HOME/logs" ]; then
    echo "WARNING: $APP_HOME/logs is not writable by $(id -un)" >&2
fi
if [ ! -w "$REPORT_DIR" ]; then
    echo "WARNING: report directory '$REPORT_DIR' is not writable by $(id -un)" >&2
    echo "         PDF reports will fail to write (squish.report.directory)." >&2
fi

# ---------------------------------------------------------------
# 4. Defaults
# ---------------------------------------------------------------
[ -n "$JAVA_OPTS" ] || JAVA_OPTS="-Xms512m -Xmx4g"
[ -n "$SPRING_PROFILES_ACTIVE" ] || SPRING_PROFILES_ACTIVE="prod"
export SPRING_PROFILES_ACTIVE

# ---------------------------------------------------------------
# 5. Go
# ---------------------------------------------------------------
# JAVA_OPTS is intentionally unquoted: it is a list of JVM flags.
# shellcheck disable=SC2086
exec "$JAVA_CMD" $JAVA_OPTS \
    --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
    -jar "$JAR_FILE" \
    --spring.profiles.active="$SPRING_PROFILES_ACTIVE"
