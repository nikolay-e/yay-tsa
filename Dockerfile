# Build stage
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY gradle gradle
COPY gradlew .
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
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
RUN chmod +x gradlew && ./gradlew :app:bootJar --no-daemon -x test

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
