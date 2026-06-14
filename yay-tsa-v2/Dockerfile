# Build stage
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# Shared root-level resource read by core-domain/shared build.gradle.kts
# (rootProject.file("../shared-constants.yaml") resolves to /app/shared-constants.yaml)
COPY shared-constants.yaml ./

WORKDIR /app/yay-tsa-v2

COPY yay-tsa-v2/gradle gradle
COPY yay-tsa-v2/gradlew .
COPY yay-tsa-v2/build.gradle.kts yay-tsa-v2/settings.gradle.kts yay-tsa-v2/gradle.properties ./

COPY yay-tsa-v2/build-logic build-logic

COPY yay-tsa-v2/core-domain/build.gradle.kts core-domain/
COPY yay-tsa-v2/core-application/build.gradle.kts core-application/
COPY yay-tsa-v2/core-testkit/build.gradle.kts core-testkit/
COPY yay-tsa-v2/infra-persistence/build.gradle.kts infra-persistence/
COPY yay-tsa-v2/infra-library-scanner/build.gradle.kts infra-library-scanner/
COPY yay-tsa-v2/infra-ml-worker/build.gradle.kts infra-ml-worker/
COPY yay-tsa-v2/infra-karaoke-worker/build.gradle.kts infra-karaoke-worker/
COPY yay-tsa-v2/infra-metadata-enricher/build.gradle.kts infra-metadata-enricher/
COPY yay-tsa-v2/infra-llm/build.gradle.kts infra-llm/
COPY yay-tsa-v2/infra-media/build.gradle.kts infra-media/
COPY yay-tsa-v2/infra-notifications/build.gradle.kts infra-notifications/
COPY yay-tsa-v2/adapter-shared/build.gradle.kts adapter-shared/
COPY yay-tsa-v2/adapter-opensubsonic/build.gradle.kts adapter-opensubsonic/
COPY yay-tsa-v2/adapter-mcp/build.gradle.kts adapter-mcp/
COPY yay-tsa-v2/adapter-mpd/build.gradle.kts adapter-mpd/
COPY yay-tsa-v2/adapter-jellyfin/build.gradle.kts adapter-jellyfin/
COPY yay-tsa-v2/app/build.gradle.kts app/

RUN chmod +x gradlew
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew dependencies --no-daemon 2>/dev/null || true

COPY yay-tsa-v2/core-domain core-domain
COPY yay-tsa-v2/core-application core-application
COPY yay-tsa-v2/core-testkit core-testkit
COPY yay-tsa-v2/infra-persistence infra-persistence
COPY yay-tsa-v2/infra-library-scanner infra-library-scanner
COPY yay-tsa-v2/infra-ml-worker infra-ml-worker
COPY yay-tsa-v2/infra-karaoke-worker infra-karaoke-worker
COPY yay-tsa-v2/infra-metadata-enricher infra-metadata-enricher
COPY yay-tsa-v2/infra-llm infra-llm
COPY yay-tsa-v2/infra-media infra-media
COPY yay-tsa-v2/infra-notifications infra-notifications
COPY yay-tsa-v2/adapter-shared adapter-shared
COPY yay-tsa-v2/adapter-opensubsonic adapter-opensubsonic
COPY yay-tsa-v2/adapter-mcp adapter-mcp
COPY yay-tsa-v2/adapter-mpd adapter-mpd
COPY yay-tsa-v2/adapter-jellyfin adapter-jellyfin
COPY yay-tsa-v2/app app

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :app:bootJar --no-daemon -x test

# Runtime stage
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -g 1000 yaytsa && adduser -u 1000 -G yaytsa -D yaytsa

# ffmpeg: audio transcoding. libwebp-tools: cwebp, for on-the-fly WebP cover thumbnails
# (musl-native, so it loads on Alpine where a JVM glibc WebP encoder would not).
RUN apk add --no-cache ffmpeg libwebp-tools

WORKDIR /app
COPY --from=builder /app/yay-tsa-v2/app/build/libs/*.jar app.jar
RUN chown -R yaytsa:yaytsa /app

USER yaytsa

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75 -XX:+UseG1GC -Djava.security.egd=file:/dev/urandom"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q --spider http://localhost:8080/System/Ping || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
