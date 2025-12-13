# Multi-stage Docker build for Yaytsa Media Server

# Stage 1: Build stage
FROM maven:3.9-eclipse-temurin-21 AS builder

# Set working directory
WORKDIR /app

# Copy Maven configuration
COPY pom.xml .

# Download dependencies (cached layer)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests -B

# Stage 2: Runtime stage
FROM eclipse-temurin:21-jre-alpine

# Install FFmpeg for transcoding support
RUN apk update && \
    apk add --no-cache \
    ffmpeg \
    tzdata \
    curl \
    && rm -rf /var/cache/apk/*

# Create non-root user
RUN addgroup -g 1000 yaytsa && \
    adduser -D -u 1000 -G yaytsa yaytsa

# Set working directory
WORKDIR /app

# Create necessary directories
RUN mkdir -p /app/media /app/temp/transcode /app/temp/images /app/logs && \
    chown -R yaytsa:yaytsa /app

# Copy JAR from builder stage
COPY --from=builder --chown=yaytsa:yaytsa /app/target/*.jar app.jar

# Copy entrypoint script and fix line endings (Windows CRLF -> Unix LF)
COPY --chown=yaytsa:yaytsa docker-entrypoint.sh /app/
RUN sed -i 's/\r$//' /app/docker-entrypoint.sh && chmod +x /app/docker-entrypoint.sh

# Switch to non-root user
USER yaytsa

# Expose port
EXPOSE 8096

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8096/manage/health || exit 1

# Set JVM options for virtual threads and container awareness
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError --enable-preview -Djava.awt.headless=true -Djava.security.egd=file:/dev/./urandom"

# Set Spring profile
ENV SPRING_PROFILES_ACTIVE=prod

# Entry point
ENTRYPOINT ["/app/docker-entrypoint.sh"]
