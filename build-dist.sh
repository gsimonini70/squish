#!/bin/bash
#
# Squish - Build Distribution Package
#

set -e

cd "$(dirname "$0")"

# ------------------------------------------------------------------
# Options
# ------------------------------------------------------------------
# The release bundle is the artifact that goes to a customer. It is built from a
# FULL, GREEN test run by default. --skip-tests exists only for fast local
# iteration and prints a loud warning; never ship a bundle built with it.
SKIP_TESTS=0
for arg in "$@"; do
    case "$arg" in
        --skip-tests)
            SKIP_TESTS=1
            ;;
        -h|--help)
            echo "Usage: $0 [--skip-tests]"
            echo ""
            echo "  --skip-tests   Build without running the test suite (LOCAL USE ONLY)."
            echo "                 Tests run by default and a failure aborts the build."
            exit 0
            ;;
        *)
            echo "ERROR: unknown option: $arg" >&2
            echo "Usage: $0 [--skip-tests]" >&2
            exit 1
            ;;
    esac
done

# pom.xml is the single source of truth for the version.
# Read the PROJECT <version>, not the <parent> (spring-boot) one, which comes first:
# skip everything between <parent> and </parent>, then take the first <version>.
VERSION=$(awk '
    /<parent>/      { in_parent = 1 }
    /<\/parent>/    { in_parent = 0; next }
    !in_parent && /<version>/ {
        gsub(/^[[:space:]]*<version>|<\/version>[[:space:]]*$/, "")
        print
        exit
    }
' pom.xml)

if [ -z "$VERSION" ]; then
    echo "ERROR: could not read <version> from pom.xml" >&2
    exit 1
fi

DIST_NAME="squish-${VERSION}"

# Build number: the git commit this bundle is built from, so several rebuilds of the same
# <version> can be told apart. Suffixed .dirty when the tree has uncommitted (tracked) changes.
# Passed to Maven as -Dgit.commit and filtered into build.properties (read at runtime by BuildInfo).
GIT_COMMIT=$(git rev-parse --short HEAD 2>/dev/null || echo unknown)
if [ "$GIT_COMMIT" != "unknown" ] && ! git diff --quiet HEAD 2>/dev/null; then
    GIT_COMMIT="${GIT_COMMIT}.dirty"
fi

echo "========================================"
echo "Building Squish Distribution Package"
echo "Version: $VERSION"
echo "Build:   $GIT_COMMIT"
echo "========================================"

# ------------------------------------------------------------------
# Build JAR
# ------------------------------------------------------------------
# No -q: a swallowed maven log turns a red test suite into an unexplained exit.
# `set -e` aborts the script on a non-zero maven exit, so a failing test here
# means NO tarball is produced.
if [ "$SKIP_TESTS" -eq 1 ]; then
    echo ""
    echo "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
    echo "!! WARNING: --skip-tests - BUILDING AN UNTESTED JAR.          !!"
    echo "!! This bundle is NOT fit to ship to a customer.              !!"
    echo "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
    echo ""
    echo "Building JAR (tests skipped)..."
    mvn clean package -DskipTests -B "-Dgit.commit=${GIT_COMMIT}"
else
    echo "Building JAR and running the test suite..."
    if ! mvn clean verify -B "-Dgit.commit=${GIT_COMMIT}"; then
        echo ""
        echo "========================================"
        echo "BUILD FAILED: the test suite is red (or compilation failed)."
        echo "No distribution package was created."
        echo "Surefire reports: target/surefire-reports/"
        echo "========================================"
        exit 1
    fi
fi

# Create distribution directory
echo "Creating distribution..."
rm -rf "target/${DIST_NAME}"
# No brace expansion: keep this portable if the script is ever run under sh.
for d in bin config docs logs reports service sql; do
    mkdir -p "target/${DIST_NAME}/$d"
done

# Copy files
# Exact name, not a glob: `mvn verify` also leaves squish-<v>.jar.original behind,
# and a glob would happily pick up a stale jar from a previous version.
if [ ! -f "target/squish-${VERSION}.jar" ]; then
    echo "ERROR: target/squish-${VERSION}.jar not found after the build" >&2
    exit 1
fi
cp "target/squish-${VERSION}.jar" "target/${DIST_NAME}/squish.jar"
cp dist/bin/* "target/${DIST_NAME}/bin/"
cp dist/config/* "target/${DIST_NAME}/config/"
cp dist/docs/* "target/${DIST_NAME}/docs/"
cp dist/service/* "target/${DIST_NAME}/service/"
cp dist/sql/* "target/${DIST_NAME}/sql/"
cp README.md "target/${DIST_NAME}/"
cp Dockerfile "target/${DIST_NAME}/"
cp docker-compose.yml "target/${DIST_NAME}/"

# docker-compose / docker build read the version from here (see Dockerfile ARG SQUISH_VERSION)
echo "SQUISH_VERSION=${VERSION}" > "target/${DIST_NAME}/.env"

# Set permissions
chmod +x "target/${DIST_NAME}/bin/"*.sh
chmod +x "target/${DIST_NAME}/service/squish.init"
# The env templates become squish.env, which holds the DB password and the
# dashboard password. Ship them 0600 so a careless `cp` cannot widen them.
chmod 600 "target/${DIST_NAME}/config/"*.template

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
if [ "$SKIP_TESTS" -eq 1 ]; then
    echo ""
    echo "  *** BUILT WITH --skip-tests - DO NOT SHIP THIS BUNDLE ***"
else
    echo "  Tests: PASSED"
fi
echo "========================================"
