FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml mvnw mvnw.cmd ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

COPY src src
RUN ./mvnw -B -DskipTests package && \
    cp target/*.jar /app/app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd --system spring && useradd --system --gid spring --home /app spring && \
    apt-get update && apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

# The Ubuntu base that eclipse-temurin:21-jre now ships on (24.04/26.04) bundles Canonical's Pebble
# service manager at /usr/bin/pebble — a ~10MB Go binary built against the Go stdlib that Trivy flags
# HIGH (CVE-2026-78667, CVE-2026-97031). We never use Pebble: the container's ENTRYPOINT runs
# `java -jar` directly, so it is dead weight. Remove it (and its empty state dir) so the final image
# carries no vulnerable gobinary.
RUN rm -rf /usr/bin/pebble /var/lib/pebble

COPY --from=build --chown=spring:spring /app/app.jar app.jar

USER spring

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10m --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health/liveness || exit 1

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
