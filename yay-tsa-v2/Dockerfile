# Build stage
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# Copy only build configuration first (cached until build files change)
COPY gradle gradle
COPY gradlew .
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle/libs.versions.toml gradle/libs.versions.toml

# Copy only build.gradle.kts from each module (for dependency resolution)
COPY core-domain/build.gradle.kts core-domain/
COPY core-application/build.gradle.kts core-application/
COPY core-testkit/build.gradle.kts core-testkit/
COPY infra-persistence/build.gradle.kts infra-persistence/
COPY infra-library-scanner/build.gradle.kts infra-library-scanner/
COPY infra-ml-worker/build.gradle.kts infra-ml-worker/
COPY infra-karaoke-worker/build.gradle.kts infra-karaoke-worker/
COPY infra-llm/build.gradle.kts infra-llm/
COPY infra-media/build.gradle.kts infra-media/
COPY infra-notifications/build.gradle.kts infra-notifications/
COPY adapter-opensubsonic/build.gradle.kts adapter-opensubsonic/
COPY adapter-mcp/build.gradle.kts adapter-mcp/
COPY adapter-mpd/build.gradle.kts adapter-mpd/
COPY adapter-jellyfin/build.gradle.kts adapter-jellyfin/
COPY app/build.gradle.kts app/

# Resolve dependencies (cached until build files change)
RUN chmod +x gradlew
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew dependencies --no-daemon 2>/dev/null || true

# Copy source code (busts cache only on source changes)
COPY core-domain core-domain
COPY core-application core-application
COPY core-testkit core-testkit
COPY infra-persistence infra-persistence
COPY infra-library-scanner infra-library-scanner
COPY infra-ml-worker infra-ml-worker
COPY infra-karaoke-worker infra-karaoke-worker
COPY infra-llm infra-llm
COPY infra-media infra-media
COPY infra-notifications infra-notifications
COPY adapter-opensubsonic adapter-opensubsonic
COPY adapter-mcp adapter-mcp
COPY adapter-mpd adapter-mpd
COPY adapter-jellyfin adapter-jellyfin
COPY app app

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :app:bootJar --no-daemon -x test

# Runtime stage
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -g 1000 yaytsa && adduser -u 1000 -G yaytsa -D yaytsa

RUN apk add --no-cache ffmpeg

WORKDIR /app
COPY --from=builder /app/app/build/libs/*.jar app.jar
RUN chown -R yaytsa:yaytsa /app

USER yaytsa

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75 -XX:+UseG1GC -Djava.security.egd=file:/dev/urandom"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q --spider http://localhost:8080/System/Ping || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
