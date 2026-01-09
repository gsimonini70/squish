#!/bin/bash
#
# Squish - Build Distribution Package
#

set -e

VERSION="2.0.0"
DIST_NAME="squish-${VERSION}"

echo "========================================"
echo "Building Squish Distribution Package"
echo "Version: $VERSION"
echo "========================================"

# Build JAR
echo "Building JAR..."
mvn clean package -DskipTests -q

# Create distribution directory
echo "Creating distribution..."
rm -rf "target/${DIST_NAME}"
mkdir -p "target/${DIST_NAME}"/{bin,config,docs,logs,service,sql}

# Copy files
cp target/pdf-compressor-modern-*.jar "target/${DIST_NAME}/squish.jar"
cp dist/bin/* "target/${DIST_NAME}/bin/"
cp dist/config/* "target/${DIST_NAME}/config/"
cp dist/docs/* "target/${DIST_NAME}/docs/"
cp dist/service/* "target/${DIST_NAME}/service/"
cp dist/sql/* "target/${DIST_NAME}/sql/"
cp README.md "target/${DIST_NAME}/"
cp Dockerfile "target/${DIST_NAME}/"
cp docker-compose.yml "target/${DIST_NAME}/"

# Set permissions
chmod +x "target/${DIST_NAME}/bin/"*.sh

# Create archives
echo "Creating archives..."
cd target

# TAR.GZ (Linux/macOS)
tar -czf "${DIST_NAME}.tar.gz" "${DIST_NAME}"

# ZIP (Windows)
zip -rq "${DIST_NAME}.zip" "${DIST_NAME}"

cd ..

echo ""
echo "========================================"
echo "Distribution packages created:"
echo "  target/${DIST_NAME}.tar.gz"
echo "  target/${DIST_NAME}.zip"
echo "========================================"
