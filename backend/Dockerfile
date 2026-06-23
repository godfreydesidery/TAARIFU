# =============================================================================
# Taarifu backend — multi-stage Docker build.
#
# Stage 1 builds the fat jar with the committed Maven wrapper (reproducible build,
# ARCHITECTURE §2). Stage 2 ships a slim JRE-only runtime image running as a
# non-root user. No secrets are baked in — all config comes from the environment
# at runtime (PRD §18, CLAUDE.md §12).
# =============================================================================

# ---- Stage 1: build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Cache dependencies first: copy only the wrapper + POM, then resolve offline deps.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

# Copy sources and build the jar (skip tests in the image; CI runs the full suite).
COPY src/ src/
RUN ./mvnw -B -q clean package -DskipTests

# ---- Stage 2: runtime ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Run as an unprivileged user (defence in depth).
RUN groupadd --system taarifu && useradd --system --gid taarifu taarifu
USER taarifu

# Copy the built jar from the build stage.
COPY --from=build /workspace/target/taarifu-backend-*.jar /app/app.jar

# The API listens on 8081 (context-path /api/v1 is set in application.yml).
EXPOSE 8081

# Container health uses the actuator liveness probe.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8081/api/v1/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
