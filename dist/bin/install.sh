#!/bin/sh
#
# Squish - Installation Script
# For Linux/macOS
#

set -e

APP_NAME="Squish"
INSTALL_DIR="${INSTALL_DIR:-/opt/squish}"
SERVICE_USER="${SERVICE_USER:-squish}"
INIT_SYSTEM=""

# Version: read from the .env that build-dist.sh writes into the bundle
# (SQUISH_VERSION=<pom version>). Never hardcode it here - it went stale at 2.0.0
# once already.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$(dirname "$SCRIPT_DIR")"
VERSION="unknown"
if [ -f "$SRC_DIR/.env" ]; then
    VERSION=$(sed -n 's/^SQUISH_VERSION=//p' "$SRC_DIR/.env" | head -1)
    [ -n "$VERSION" ] || VERSION="unknown"
fi

# Colors (if terminal supports)
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

print_banner() {
    echo ""
    echo "========================================"
    echo "  $APP_NAME v$VERSION - Installer"
    echo "  Designed by Lucsartech Srl"
    echo "========================================"
    echo ""
}

print_ok() {
    echo "${GREEN}[OK]${NC} $1"
}

print_warn() {
    echo "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo "${RED}[ERROR]${NC} $1"
}

detect_init_system() {
    if command -v systemctl > /dev/null 2>&1 && systemctl --version > /dev/null 2>&1; then
        INIT_SYSTEM="systemd"
    elif command -v chkconfig > /dev/null 2>&1; then
        INIT_SYSTEM="sysv-redhat"
    elif command -v update-rc.d > /dev/null 2>&1; then
        INIT_SYSTEM="sysv-debian"
    else
        INIT_SYSTEM="none"
    fi
    echo "Detected init system: $INIT_SYSTEM"
}

check_root() {
    if [ "$(id -u)" -ne 0 ]; then
        print_error "This script must be run as root (use sudo)"
        exit 1
    fi
}

check_java() {
    echo "Checking Java..."
    if ! command -v java > /dev/null 2>&1; then
        print_error "Java not found. Please install Java 22 or higher."
        exit 1
    fi
    
    JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VERSION" -lt 22 ] 2>/dev/null; then
        print_warn "Java 22+ recommended. Found: $JAVA_VERSION"
    else
        print_ok "Java $JAVA_VERSION found"
    fi
}

create_user() {
    echo "Creating service user '$SERVICE_USER'..."
    if id "$SERVICE_USER" > /dev/null 2>&1; then
        print_ok "User '$SERVICE_USER' already exists"
    else
        useradd -r -s /sbin/nologin -d "$INSTALL_DIR" "$SERVICE_USER" 2>/dev/null || \
        useradd -r -s /bin/false -d "$INSTALL_DIR" "$SERVICE_USER"
        print_ok "User '$SERVICE_USER' created"
    fi
}

install_files() {
    echo "Installing files to $INSTALL_DIR..."

    # Create directories. NOTE: no brace expansion - this script runs under
    # /bin/sh, which is dash on Debian/Ubuntu, and dash would create a single
    # literal directory named '{bin,config,logs,service}'.
    # 'reports' is squish.report.directory (default 'reports', relative to the
    # working directory). Without it, every PDF report write fails at the end of
    # a run - and under systemd's ProtectSystem=strict it must also be listed in
    # ReadWritePaths= (it is, in dist/service/squish.service).
    mkdir -p "$INSTALL_DIR/bin"
    mkdir -p "$INSTALL_DIR/config"
    mkdir -p "$INSTALL_DIR/logs"
    mkdir -p "$INSTALL_DIR/reports"
    mkdir -p "$INSTALL_DIR/service"
    mkdir -p "$INSTALL_DIR/sql"

    # Copy files
    cp "$SRC_DIR/squish.jar" "$INSTALL_DIR/" 2>/dev/null || \
        { print_error "squish.jar not found in $SRC_DIR"; exit 1; }

    # squish-run.sh is the launcher shared by systemd, the SysV init script and
    # squish.sh - the service will not start without it.
    for f in squish.sh squish-run.sh preflight.sh uninstall.sh; do
        if [ -f "$SCRIPT_DIR/$f" ]; then
            cp "$SCRIPT_DIR/$f" "$INSTALL_DIR/bin/"
            chmod 755 "$INSTALL_DIR/bin/$f"
        else
            print_warn "$f not found in the bundle - skipping"
        fi
    done

    # SQL (tracking table DDL) - preflight.sh points the operator at it
    cp "$SRC_DIR"/sql/*.sql "$INSTALL_DIR/sql/" 2>/dev/null || true

    # Copy config if not exists
    if [ ! -f "$INSTALL_DIR/config/application.yml" ]; then
        cp "$SRC_DIR/config/application.yml" "$INSTALL_DIR/config/" 2>/dev/null || true
    fi

    if [ ! -f "$INSTALL_DIR/config/squish.env" ]; then
        cp "$SRC_DIR/config/squish.env.template" "$INSTALL_DIR/config/squish.env" 2>/dev/null || \
            { print_error "config/squish.env.template not found in the bundle"; exit 1; }
        print_ok "config/squish.env created from the template - EDIT IT before starting"
    else
        print_warn "config/squish.env already exists - left untouched"
    fi

    # squish.env holds the DB password AND (now) the dashboard password.
    # Enforce 0600 every time, not just on first install: an operator who edited
    # the file with a permissive umask would otherwise leave it world-readable.
    chmod 600 "$INSTALL_DIR/config/squish.env"
    chmod 750 "$INSTALL_DIR/config"

    # Set ownership
    chown -R "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR"

    # The service user must be able to write logs/ and reports/ at runtime.
    chmod 750 "$INSTALL_DIR/logs" "$INSTALL_DIR/reports"

    print_ok "Files installed"
}

warn_sysv_supervision() {
    echo ""
    print_warn "=============================================================="
    print_warn " SysV init has NO respawn. Squish exits non-zero on a fatal"
    print_warn " failure (e.g. OutOfMemoryError in watchdog mode) and expects a"
    print_warn " supervisor to restart it. This init script cannot do that."
    print_warn ""
    print_warn " Supervise it with cron - add to root's crontab:"
    print_warn "   * * * * * /etc/init.d/squish check >> $INSTALL_DIR/logs/respawn.log 2>&1"
    print_warn ""
    print_warn " (or inittab respawn / monit / supervisord - see the comments at"
    print_warn "  the top of /etc/init.d/squish). systemd is the supported path."
    print_warn "=============================================================="
    echo ""
}

install_service() {
    echo "Installing service..."

    case "$INIT_SYSTEM" in
        systemd)
            cp "$SRC_DIR/service/squish.service" /etc/systemd/system/squish.service
            # The unit hardcodes /opt/squish. Rewrite it if we installed elsewhere.
            if [ "$INSTALL_DIR" != "/opt/squish" ]; then
                sed -i "s|/opt/squish|$INSTALL_DIR|g" /etc/systemd/system/squish.service
                print_warn "Unit paths rewritten from /opt/squish to $INSTALL_DIR"
            fi
            systemctl daemon-reload
            systemctl enable squish
            print_ok "Systemd service installed and enabled (Restart=on-failure)"
            ;;
        sysv-redhat)
            cp "$SRC_DIR/service/squish.init" /etc/init.d/squish
            chmod +x /etc/init.d/squish
            chkconfig --add squish
            chkconfig squish on
            print_ok "SysV init script installed (chkconfig)"
            warn_sysv_supervision
            ;;
        sysv-debian)
            cp "$SRC_DIR/service/squish.init" /etc/init.d/squish
            chmod +x /etc/init.d/squish
            update-rc.d squish defaults
            print_ok "SysV init script installed (update-rc.d)"
            warn_sysv_supervision
            ;;
        *)
            print_warn "No init system detected. Service not installed."
            print_warn "Use $INSTALL_DIR/bin/squish.sh to start manually."
            print_warn "Nothing will restart Squish if it dies - see bin/squish.sh header."
            ;;
    esac
}

create_symlink() {
    echo "Creating command symlink..."
    ln -sf "$INSTALL_DIR/bin/squish.sh" /usr/local/bin/squish 2>/dev/null || \
        ln -sf "$INSTALL_DIR/bin/squish.sh" /usr/bin/squish
    print_ok "Command 'squish' available system-wide"
}

print_summary() {
    echo ""
    echo "========================================"
    echo "  Installation Complete!"
    echo "========================================"
    echo ""
    echo "Installation directory: $INSTALL_DIR"
    echo "Configuration: $INSTALL_DIR/config/"
    echo "Logs:    $INSTALL_DIR/logs/"
    echo "Reports: $INSTALL_DIR/reports/"
    echo ""
    echo "NEXT STEPS - in this order:"
    echo ""
    echo "  1. Edit the configuration (DB credentials, dashboard password):"
    echo "       sudo vi $INSTALL_DIR/config/squish.env"
    echo "     Set SECURITY_PASSWORD explicitly. If you leave it empty, a random"
    echo "     password is generated and only logged once, at WARN level."
    echo ""
    echo "  2. Create the tracking table (once, per schema):"
    echo "       sqlplus user/pass@db @$INSTALL_DIR/sql/create_tracking_table.sql"
    echo ""
    echo "  3. Run the preflight check:"
    echo "       sudo -u $SERVICE_USER $INSTALL_DIR/bin/preflight.sh"
    echo ""
    echo "  4. Start:"
    echo ""
    echo "Commands:"
    case "$INIT_SYSTEM" in
        systemd)
            echo "  sudo systemctl start squish"
            echo "  sudo systemctl stop squish"
            echo "  sudo systemctl status squish"
            ;;
        sysv-*)
            echo "  sudo service squish start"
            echo "  sudo service squish stop"
            echo "  sudo service squish status"
            ;;
        *)
            echo "  sudo squish start"
            echo "  sudo squish stop"
            echo "  sudo squish status"
            ;;
    esac
    echo ""
    echo "Dashboard: http://localhost:8080/"
    echo ""
}

# Main
print_banner
check_root
check_java
detect_init_system
create_user
install_files
install_service
create_symlink
print_summary
