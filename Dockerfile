# syntax=docker/dockerfile:1.7
#
# EduCore backend image: multi-stage build from source (no pre-built ./target jar needed).
#
# Build arguments:
#   SKIP_TESTS=false (default)  runs `mvn verify` (unit tests + Testcontainers integration tests).
#                               The integration tests start PostgreSQL containers, so the build needs a
#                               Docker daemon. A RUN step cannot mount the host socket, so CI exposes it
#                               on a loopback TCP port (socat) and builds with host networking
#                               (docs/ops/CI.md, .github/workflows/supply-chain.yml):
#                                 docker buildx build --network host --allow network.host \
#                                   --build-arg TESTCONTAINERS_DOCKER_HOST=tcp://127.0.0.1:<port> -t educore-backend .
#                               This needs a Linux Docker host: Docker Desktop does not route published
#                               container ports to a host-network build.
#   TESTCONTAINERS_DOCKER_HOST  daemon endpoint for the test run (build stage only; not in the final image).
#   SKIP_TESTS=true             runs `mvn package -Dmaven.test.skip=true` (local compose builds; tests already ran in CI).
#
# Base images are pinned by digest; Dependabot (.github/dependabot.yml) proposes digest updates weekly.

FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
ARG SKIP_TESTS=false
ARG TESTCONTAINERS_DOCKER_HOST=""
WORKDIR /workspace

COPY pom.xml ./
# Resolve plugins and dependencies in a cached layer that only changes with pom.xml.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q dependency:resolve dependency:resolve-plugins

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    set -eu; \
    if [ "$SKIP_TESTS" = "true" ]; then \
      mvn -B -ntp -Dmaven.test.skip=true package; \
    else \
      if [ -z "$TESTCONTAINERS_DOCKER_HOST" ]; then \
        echo "SKIP_TESTS=false needs a Docker daemon for Testcontainers: pass --network host and --build-arg TESTCONTAINERS_DOCKER_HOST=tcp://127.0.0.1:<port> (docs/ops/CI.md), or --build-arg SKIP_TESTS=true" >&2; \
        exit 1; \
      fi; \
      DOCKER_HOST="$TESTCONTAINERS_DOCKER_HOST" TESTCONTAINERS_HOST_OVERRIDE=localhost mvn -B -ntp verify; \
    fi; \
    cp target/educore-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555
WORKDIR /app

# Fixed non-root uid/gid 10001 so bind-mounted folders (csv_uploads) can be chowned predictably on Linux hosts.
# /var/lib/educore is the mount point of the erasure ledger volume (docker-compose.yml); a new named volume
# inherits its owner, so the backend can append to the ledger; the backup sidecar (uid 70) may read it
# (keyed digests only, no personal data).
RUN addgroup -S -g 10001 educore \
    && adduser -S -D -H -u 10001 -G educore educore \
    && mkdir -p /app/csv_uploads/inbox /app/csv_uploads/processing /app/csv_uploads/done /app/csv_uploads/failed \
    && chown -R educore:educore /app \
    && mkdir -p /var/lib/educore && chown educore:educore /var/lib/educore && chmod 0755 /var/lib/educore

COPY --from=build --chown=educore:educore /workspace/app.jar /app/app.jar

USER 10001:10001

# API port. The Actuator management port 9090 is internal only and deliberately not exposed;
# docker-compose does not publish it either.
EXPOSE 8080

# MaxRAMPercentage keeps heap + metaspace + threads inside the container memory limit (compose: 1 GB).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
