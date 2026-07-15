#!/bin/sh
#
# Squish - Preflight Check
# ============================================
# Run this AFTER editing config/squish.env and BEFORE the first start.
# It catches the boring failures that otherwise show up as a support call:
#
#   - Java missing or older than 22
#   - config/squish.env missing, or world-readable while holding the DB password
#     AND the dashboard password
#   - the JDBC host:port not reachable (plain TCP connect - no sqlplus needed)
#   - HTTP_SSL_ENABLED=true with a keystore the service user cannot read
#     (the app FAILS FAST on this at startup rather than silently serving plaintext)
#   - SECURITY_ENABLED=true with no SECURITY_PASSWORD (a random one gets generated
#     and only logged at WARN - nobody ever finds it)
#   - logs/ and reports/ not writable (squish.report.directory defaults to 'reports')
#   - the tracking table (checked only if sqlplus happens to be installed)
#
# POSIX sh. No dependencies beyond coreutils; every optional probe degrades to SKIP.
#
# Usage: bin/preflight.sh          # exits 0 if all checks pass, 1 otherwise
#

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_HOME="${SQUISH_HOME:-$(dirname "$SCRIPT_DIR")}"
ENV_FILE="$APP_HOME/config/squish.env"

FAILURES=0
WARNINGS=0

pass() { echo "[ OK   ] $1"; }
warn() { echo "[ WARN ] $1"; WARNINGS=$((WARNINGS + 1)); }
fail() { echo "[ FAIL ] $1"; FAILURES=$((FAILURES + 1)); }
skip() { echo "[ SKIP ] $1"; }

echo ""
echo "========================================"
echo "  Squish - Preflight Check"
echo "========================================"
echo "App home: $APP_HOME"
echo ""

# ---------------------------------------------------------------
# 1. Environment file: present, and not readable by the world
# ---------------------------------------------------------------
if [ ! -f "$ENV_FILE" ]; then
    fail "config/squish.env not found. Copy config/squish.env.template and edit it."
else
    pass "config/squish.env exists"

    # stat(1) differs between GNU and BSD/macOS; try both.
    PERMS=$(stat -c '%a' "$ENV_FILE" 2>/dev/null || stat -f '%Lp' "$ENV_FILE" 2>/dev/null)
    if [ -z "$PERMS" ]; then
        skip "cannot determine permissions of squish.env (no usable stat)"
    elif [ "$PERMS" = "600" ]; then
        pass "config/squish.env is chmod 600"
    else
        fail "config/squish.env is chmod $PERMS - it holds the DB password AND the
         dashboard password. Fix with: chmod 600 $ENV_FILE"
    fi

    set -a
    # shellcheck disable=SC1090
    . "$ENV_FILE"
    set +a
fi

# ---------------------------------------------------------------
# 2. Java 22+
# ---------------------------------------------------------------
if [ -n "$JAVA_HOME" ]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
else
    JAVA_CMD="$(command -v java 2>/dev/null)"
fi

if [ -z "$JAVA_CMD" ] || [ ! -x "$JAVA_CMD" ]; then
    fail "Java not found (JAVA_HOME='$JAVA_HOME', and no 'java' on PATH)"
else
    JAVA_VERSION=$("$JAVA_CMD" -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ -z "$JAVA_VERSION" ]; then
        warn "could not parse the version of $JAVA_CMD"
    elif [ "$JAVA_VERSION" -lt 22 ] 2>/dev/null; then
        fail "Java 22+ required (virtual threads). Found: $JAVA_VERSION at $JAVA_CMD"
    else
        pass "Java $JAVA_VERSION at $JAVA_CMD"
    fi
fi

# ---------------------------------------------------------------
# 3. The jar
# ---------------------------------------------------------------
if [ -f "$APP_HOME/squish.jar" ]; then
    pass "squish.jar present"
else
    fail "squish.jar not found in $APP_HOME"
fi

# ---------------------------------------------------------------
# 4. Required settings
# ---------------------------------------------------------------
[ -n "$DB_URL" ]      && pass "DB_URL is set"      || fail "DB_URL is not set in squish.env"
[ -n "$DB_USER" ]     && pass "DB_USER is set"     || fail "DB_USER is not set in squish.env"
[ -n "$DB_PASSWORD" ] && pass "DB_PASSWORD is set" || fail "DB_PASSWORD is not set in squish.env"

# ---------------------------------------------------------------
# 5. JDBC reachability - plain TCP connect to host:port
#    Handles both  jdbc:oracle:thin:@//host:port/SERVICE
#              and jdbc:oracle:thin:@host:port:SID
# ---------------------------------------------------------------
if [ -n "$DB_URL" ]; then
    HOSTPORT=$(echo "$DB_URL" | sed -e 's|^.*@//||' -e 's|^.*@||' -e 's|[/:][^:/]*$||')
    DB_HOST=$(echo "$HOSTPORT" | cut -d: -f1)
    DB_PORT=$(echo "$HOSTPORT" | cut -d: -f2)

    if [ -z "$DB_HOST" ] || [ -z "$DB_PORT" ]; then
        skip "could not parse host:port out of DB_URL - not testing connectivity"
    elif command -v nc > /dev/null 2>&1; then
        if nc -z -w 5 "$DB_HOST" "$DB_PORT" > /dev/null 2>&1; then
            pass "TCP connect to $DB_HOST:$DB_PORT succeeded"
        else
            fail "cannot reach the database at $DB_HOST:$DB_PORT (firewall? listener down?)"
        fi
    elif command -v bash > /dev/null 2>&1; then
        if bash -c "exec 3<>/dev/tcp/$DB_HOST/$DB_PORT" > /dev/null 2>&1; then
            pass "TCP connect to $DB_HOST:$DB_PORT succeeded"
        else
            fail "cannot reach the database at $DB_HOST:$DB_PORT (firewall? listener down?)"
        fi
    else
        skip "neither nc nor bash available - not testing DB connectivity"
    fi
fi

# ---------------------------------------------------------------
# 6. HTTPS keystore - the app fails fast if this is unreadable
# ---------------------------------------------------------------
if [ "$HTTP_SSL_ENABLED" = "true" ]; then
    if [ -z "$HTTP_KEYSTORE_PATH" ]; then
        fail "HTTP_SSL_ENABLED=true but HTTP_KEYSTORE_PATH is empty - startup will fail"
    elif [ ! -f "$HTTP_KEYSTORE_PATH" ]; then
        fail "keystore not found: $HTTP_KEYSTORE_PATH - startup will fail"
    elif [ ! -r "$HTTP_KEYSTORE_PATH" ]; then
        fail "keystore not readable by $(id -un): $HTTP_KEYSTORE_PATH - startup will fail"
    else
        pass "keystore readable: $HTTP_KEYSTORE_PATH"
        [ -n "$HTTP_KEYSTORE_PASSWORD" ] || warn "HTTP_KEYSTORE_PASSWORD is empty"
    fi
else
    skip "HTTPS disabled (HTTP_SSL_ENABLED != true)"
fi

# ---------------------------------------------------------------
# 7. Dashboard authentication
# ---------------------------------------------------------------
if [ "$SECURITY_ENABLED" = "false" ]; then
    warn "SECURITY_ENABLED=false - the write endpoints (/api/config, /api/compress,
         /api/thumbnail) are UNAUTHENTICATED. Only acceptable on a closed LAN."
else
    if [ -z "$SECURITY_PASSWORD" ]; then
        warn "SECURITY_PASSWORD is empty - a random password will be generated and
         only printed once, at WARN level, in the log. Set it explicitly."
    else
        pass "SECURITY_PASSWORD is set (user: ${SECURITY_USERNAME:-admin})"
    fi
fi

# ---------------------------------------------------------------
# 8. Writable runtime directories
# ---------------------------------------------------------------
REPORT_DIR="${REPORT_DIRECTORY:-reports}"
case "$REPORT_DIR" in
    /*) ;;                                  # absolute
    *) REPORT_DIR="$APP_HOME/$REPORT_DIR" ;; # relative to the working directory
esac

for d in "$APP_HOME/logs" "$REPORT_DIR"; do
    if [ ! -d "$d" ]; then
        fail "directory missing: $d"
    elif [ ! -w "$d" ]; then
        fail "directory not writable by $(id -un): $d"
    else
        pass "writable: $d"
    fi
done

# ---------------------------------------------------------------
# 9. Dashboard port already taken?
# ---------------------------------------------------------------
PORT="${HTTP_PORT:-8080}"
if command -v nc > /dev/null 2>&1; then
    if nc -z -w 2 127.0.0.1 "$PORT" > /dev/null 2>&1; then
        warn "something is already listening on port $PORT - Squish will fail to bind"
    else
        pass "port $PORT is free"
    fi
else
    skip "nc not available - not checking whether port $PORT is free"
fi

# ---------------------------------------------------------------
# 10. Tracking table (best effort - needs sqlplus, which we do not require)
# ---------------------------------------------------------------
TRACKING="${TRACKING_TABLE:-SQUISH_PROCESSED}"
if ! command -v sqlplus > /dev/null 2>&1; then
    skip "sqlplus not installed - cannot verify the $TRACKING tracking table.
         Make sure sql/create_tracking_table.sql has been run once."
elif [ -z "$DB_USER" ] || [ -z "$DB_PASSWORD" ]; then
    skip "no DB credentials - cannot verify the $TRACKING tracking table."
else
    EZ=$(echo "$DB_URL" | sed -e 's|^jdbc:oracle:thin:@//||' -e 's|^jdbc:oracle:thin:@||')
    # all_tables, not user_tables: the tracking table is not necessarily owned by the
    # connecting user (it may live in the data owner's schema and be reached through a
    # grant). What matters is that Squish can SEE it, which is exactly what all_tables
    # answers. The ROWNUM subquery keeps the result a strict 0 or 1 even if a table of
    # that name is visible in more than one schema.
    RAW=$(sqlplus -s -L "$DB_USER/$DB_PASSWORD@$EZ" <<EOF 2>&1
set heading off feedback off pagesize 0
SELECT COUNT(*) FROM (
  SELECT 1 FROM all_tables WHERE table_name = UPPER('$TRACKING') AND ROWNUM = 1
);
exit
EOF
)
    # Keep digits only. Do NOT enumerate the whitespace to strip ("tr -d ' \r\n'"):
    # SQL*Plus pads its output with characters that vary by platform and NLS settings,
    # and a single unexpected one (a tab, a stray CR) leaves the value unequal to "1"
    # and sends a perfectly healthy install down the error branch.
    COUNT=$(printf '%s' "$RAW" | tr -dc '0-9')
    if [ "$COUNT" = "1" ]; then
        pass "tracking table $TRACKING exists"
    elif [ "$COUNT" = "0" ]; then
        fail "tracking table $TRACKING does not exist. Create it with:
         sqlplus $DB_USER/***@$EZ @$APP_HOME/sql/create_tracking_table.sql"
    else
        # Report what sqlplus actually said. Guessing "login failed?" is worse than
        # useless when the login succeeded and something else went wrong.
        skip "could not verify the $TRACKING tracking table. sqlplus said:
         $(printf '%s' "$RAW" | tr '\n' ' ' | cut -c1-120)"
    fi
fi

# ---------------------------------------------------------------
echo ""
echo "========================================"
if [ "$FAILURES" -gt 0 ]; then
    echo "  PREFLIGHT FAILED: $FAILURES error(s), $WARNINGS warning(s)"
    echo "========================================"
    echo ""
    exit 1
fi
echo "  PREFLIGHT PASSED ($WARNINGS warning(s))"
echo "========================================"
echo ""
exit 0
