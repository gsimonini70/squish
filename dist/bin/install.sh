#!/bin/sh
#
# Squish - Installation Script
# For Linux/macOS
#

set -e

APP_NAME="Squish"
VERSION="2.0.0"
INSTALL_DIR="${INSTALL_DIR:-/opt/squish}"
SERVICE_USER="${SERVICE_USER:-squish}"
INIT_SYSTEM=""

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
    
    # Create directories
    mkdir -p "$INSTALL_DIR"/{bin,config,logs,service}
    
    # Get source directory
    SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
    SRC_DIR="$(dirname "$SCRIPT_DIR")"
    
    # Copy files
    cp "$SRC_DIR/squish.jar" "$INSTALL_DIR/" 2>/dev/null || \
        cp "$SCRIPT_DIR/../squish.jar" "$INSTALL_DIR/" 2>/dev/null || \
        { print_error "squish.jar not found"; exit 1; }
    
    cp "$SCRIPT_DIR/squish.sh" "$INSTALL_DIR/bin/"
    chmod +x "$INSTALL_DIR/bin/squish.sh"
    
    # Copy config if not exists
    if [ ! -f "$INSTALL_DIR/config/application.yml" ]; then
        cp "$SRC_DIR/config/application.yml" "$INSTALL_DIR/config/" 2>/dev/null || \
            cp "$SCRIPT_DIR/../config/application.yml" "$INSTALL_DIR/config/" 2>/dev/null || true
    fi
    
    if [ ! -f "$INSTALL_DIR/config/squish.env" ]; then
        cp "$SRC_DIR/config/squish.env.template" "$INSTALL_DIR/config/squish.env" 2>/dev/null || \
            cp "$SCRIPT_DIR/../config/squish.env.template" "$INSTALL_DIR/config/squish.env" 2>/dev/null || true
        chmod 600 "$INSTALL_DIR/config/squish.env"
    fi
    
    # Set ownership
    chown -R "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR"
    
    print_ok "Files installed"
}

install_service() {
    echo "Installing service..."
    
    SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
    SRC_DIR="$(dirname "$SCRIPT_DIR")"
    
    case "$INIT_SYSTEM" in
        systemd)
            cp "$SRC_DIR/service/squish.service" /etc/systemd/system/ 2>/dev/null || \
                cp "$SCRIPT_DIR/../service/squish.service" /etc/systemd/system/
            systemctl daemon-reload
            systemctl enable squish
            print_ok "Systemd service installed and enabled"
            ;;
        sysv-redhat)
            cp "$SRC_DIR/service/squish.init" /etc/init.d/squish 2>/dev/null || \
                cp "$SCRIPT_DIR/../service/squish.init" /etc/init.d/squish
            chmod +x /etc/init.d/squish
            chkconfig --add squish
            chkconfig squish on
            print_ok "SysV init script installed (chkconfig)"
            ;;
        sysv-debian)
            cp "$SRC_DIR/service/squish.init" /etc/init.d/squish 2>/dev/null || \
                cp "$SCRIPT_DIR/../service/squish.init" /etc/init.d/squish
            chmod +x /etc/init.d/squish
            update-rc.d squish defaults
            print_ok "SysV init script installed (update-rc.d)"
            ;;
        *)
            print_warn "No init system detected. Service not installed."
            print_warn "Use $INSTALL_DIR/bin/squish.sh to start manually."
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
    echo "Logs: $INSTALL_DIR/logs/"
    echo ""
    echo "IMPORTANT: Edit configuration before starting:"
    echo "  sudo vi $INSTALL_DIR/config/squish.env"
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
