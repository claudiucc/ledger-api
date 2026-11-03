# ---- Build stage -------------------------------------------------------
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /workspace

# Cache dependencies: copy only the files Gradle needs to resolve them first.
COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew \
    && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# Now copy the sources and build the real artifact.
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test \
    && find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -exec cp {} app.jar \;

# ---- Runtime stage -------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system spring \
    && useradd --system --gid spring spring

WORKDIR /app
COPY --from=builder /workspace/app.jar ./app.jar
RUN chown spring:spring /app/app.jar
USER spring

ENV SERVER_PORT=8080
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --start-period=40s --retries=10 \
  CMD curl -fsS "http://localhost:${SERVER_PORT}/actuator/health" | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

