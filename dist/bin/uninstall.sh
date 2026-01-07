#!/bin/sh
#
# Squish - Uninstallation Script
# For Linux/macOS
#

set -e

APP_NAME="Squish"
INSTALL_DIR="${INSTALL_DIR:-/opt/squish}"
SERVICE_USER="${SERVICE_USER:-squish}"
INIT_SYSTEM=""
KEEP_CONFIG=0
KEEP_LOGS=0

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

print_banner() {
    echo ""
    echo "========================================"
    echo "  $APP_NAME - Uninstaller"
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

usage() {
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  --keep-config    Keep configuration files"
    echo "  --keep-logs      Keep log files"
    echo "  --keep-all       Keep config and logs"
    echo "  -y, --yes        Skip confirmation"
    echo "  -h, --help       Show this help"
    echo ""
    exit 0
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
}

check_root() {
    if [ "$(id -u)" -ne 0 ]; then
        print_error "This script must be run as root (use sudo)"
        exit 1
    fi
}

stop_service() {
    echo "Stopping service..."
    
    case "$INIT_SYSTEM" in
        systemd)
            systemctl stop squish 2>/dev/null || true
            systemctl disable squish 2>/dev/null || true
            print_ok "Service stopped"
            ;;
        sysv-*)
            service squish stop 2>/dev/null || true
            print_ok "Service stopped"
            ;;
        *)
            # Try to stop via PID file
            if [ -f "$INSTALL_DIR/squish.pid" ]; then
                PID=$(cat "$INSTALL_DIR/squish.pid")
                kill "$PID" 2>/dev/null || true
                rm -f "$INSTALL_DIR/squish.pid"
            fi
            ;;
    esac
}

remove_service() {
    echo "Removing service..."
    
    case "$INIT_SYSTEM" in
        systemd)
            rm -f /etc/systemd/system/squish.service
            systemctl daemon-reload
            print_ok "Systemd service removed"
            ;;
        sysv-redhat)
            chkconfig --del squish 2>/dev/null || true
            rm -f /etc/init.d/squish
            print_ok "SysV init script removed"
            ;;
        sysv-debian)
            update-rc.d -f squish remove 2>/dev/null || true
            rm -f /etc/init.d/squish
            print_ok "SysV init script removed"
            ;;
        *)
            print_warn "No init system service to remove"
            ;;
    esac
}

remove_symlink() {
    echo "Removing command symlink..."
    rm -f /usr/local/bin/squish 2>/dev/null || true
    rm -f /usr/bin/squish 2>/dev/null || true
    print_ok "Symlink removed"
}

remove_files() {
    echo "Removing files..."
    
    if [ ! -d "$INSTALL_DIR" ]; then
        print_warn "Installation directory not found: $INSTALL_DIR"
        return
    fi
    
    # Remove JAR and bin
    rm -f "$INSTALL_DIR/squish.jar"
    rm -rf "$INSTALL_DIR/bin"
    rm -rf "$INSTALL_DIR/service"
    rm -f "$INSTALL_DIR/squish.pid"
    
    # Config
    if [ "$KEEP_CONFIG" -eq 0 ]; then
        rm -rf "$INSTALL_DIR/config"
        print_ok "Configuration removed"
    else
        print_warn "Configuration kept: $INSTALL_DIR/config/"
    fi
    
    # Logs
    if [ "$KEEP_LOGS" -eq 0 ]; then
        rm -rf "$INSTALL_DIR/logs"
        rm -f "$INSTALL_DIR/squish.log"
        print_ok "Logs removed"
    else
        print_warn "Logs kept: $INSTALL_DIR/logs/"
    fi
    
    # Remove directory if empty
    rmdir "$INSTALL_DIR" 2>/dev/null || true
    
    print_ok "Files removed"
}

remove_user() {
    echo "Removing service user..."
    
    if id "$SERVICE_USER" > /dev/null 2>&1; then
        userdel "$SERVICE_USER" 2>/dev/null || true
        print_ok "User '$SERVICE_USER' removed"
    else
        print_warn "User '$SERVICE_USER' not found"
    fi
}

remove_env_files() {
    rm -f /etc/sysconfig/squish 2>/dev/null || true
    rm -f /etc/default/squish 2>/dev/null || true
}

confirm() {
    if [ "$SKIP_CONFIRM" = "1" ]; then
        return 0
    fi
    
    echo "This will uninstall $APP_NAME from $INSTALL_DIR"
    echo ""
    printf "Are you sure? [y/N] "
    read -r answer
    case "$answer" in
        [yY][eE][sS]|[yY])
            return 0
            ;;
        *)
            echo "Aborted."
            exit 0
            ;;
    esac
}

print_summary() {
    echo ""
    echo "========================================"
    echo "  Uninstallation Complete!"
    echo "========================================"
    echo ""
    if [ "$KEEP_CONFIG" -eq 1 ] || [ "$KEEP_LOGS" -eq 1 ]; then
        echo "Some files were kept in: $INSTALL_DIR"
    fi
    echo ""
}

# Parse arguments
SKIP_CONFIRM=0
while [ $# -gt 0 ]; do
    case "$1" in
        --keep-config)
            KEEP_CONFIG=1
            ;;
        --keep-logs)
            KEEP_LOGS=1
            ;;
        --keep-all)
            KEEP_CONFIG=1
            KEEP_LOGS=1
            ;;
        -y|--yes)
            SKIP_CONFIRM=1
            ;;
        -h|--help)
            usage
            ;;
        *)
            print_error "Unknown option: $1"
            usage
            ;;
    esac
    shift
done

# Main
print_banner
check_root
detect_init_system
confirm
stop_service
remove_service
remove_symlink
remove_files
remove_env_files
remove_user
print_summary
