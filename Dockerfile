# ============================================
# Squish - Docker Image
# ============================================
# The version is NOT hardcoded here: pom.xml <version> is the single source of truth.
# Pass it in (build-dist.sh writes it to the dist .env; docker-compose forwards it):
#
# Build: docker build --build-arg SQUISH_VERSION=$(mvn -q help:evaluate -Dexpression=project.version -DforceStdout) -t squish:3.0.0 .
# Run:   docker run -p 8080:8080 -e DB_URL=... squish:3.0.0

FROM eclipse-temurin:22-jre-alpine

# Defaults to "dev" when the caller does not pass a version.
ARG SQUISH_VERSION=dev

LABEL maintainer="Lucsartech Srl"
LABEL description="Squish - PDF Compression Engine"
LABEL version="${SQUISH_VERSION}"

# Create app user
RUN addgroup -S squish && adduser -S squish -G squish

WORKDIR /app

# Copy JAR
COPY target/squish-*.jar squish.jar

# Create directories
RUN mkdir -p logs config && chown -R squish:squish /app

USER squish

# Environment defaults
ENV JAVA_OPTS="-Xms256m -Xmx1g" \
    SPRING_PROFILES_ACTIVE="prod"

# Expose dashboard port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -q --spider http://localhost:8080/api/health || exit 1

# Start application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar squish.jar --spring.profiles.active=$SPRING_PROFILES_ACTIVE"]
