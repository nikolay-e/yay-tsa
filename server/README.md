# Yaytsa Media Server

A high-performance, Jellyfin-compatible media server built with Java 21 and Spring Boot, designed for streaming music collections with modern architecture and virtual threads support.

## Overview

Yaytsa Media Server is a modular monolith following the ports/adapters (hexagonal) architecture pattern. It provides:

- 🎵 **Music Library Management** - Scan, organize, and serve music collections
- 🔊 **Audio Streaming** - Direct streaming with byte-range support (RFC 9110)
- 🔄 **Transcoding** - FFmpeg-based on-the-fly transcoding
- 📝 **Playlist Management** - Create and manage user playlists
- 📊 **Playback Tracking** - Session management and scrobbling
- 🖼️ **Artwork Handling** - Image extraction, caching, and resizing
- 🔐 **Secure Authentication** - Device-bound opaque tokens with BCrypt

## Technology Stack

- **Runtime**: Java 21 with Virtual Threads
- **Framework**: Spring Boot 3.3 (MVC, not reactive)
- **Database**: PostgreSQL 15+
- **ORM**: Spring Data JPA with Specifications
- **Migrations**: Flyway
- **Audio Processing**: jaudiotagger
- **Transcoding**: FFmpeg
- **Image Processing**: Thumbnailator
- **Caching**: Caffeine
- **Monitoring**: Micrometer + Prometheus
- **API Documentation**: SpringDoc OpenAPI

## Prerequisites

- Java 21 or higher
- Maven 3.9+
- PostgreSQL 15+
- FFmpeg (for transcoding)
- Docker & Docker Compose (optional)

## Quick Start

### Using Docker Compose (Recommended)

1. Clone the repository:

```bash
git clone https://github.com/yourusername/yaytsa.git
cd yaytsa/server
```

1. Copy and configure environment variables:

```bash
cp .env.example .env
# Edit .env with your configuration
```

1. Start the services:

```bash
# Development mode with hot reload
docker-compose up

# Production mode
docker-compose --profile prod up -d

# With monitoring (Prometheus + Grafana)
docker-compose --profile monitoring up -d
```

1. Access the services:

- Media Server: <http://localhost:8080>
- Swagger UI: <http://localhost:8080/swagger-ui.html>
- pgAdmin (dev): <http://localhost:5050>
- Prometheus (monitoring): <http://localhost:9090>
- Grafana (monitoring): <http://localhost:3000>

### Manual Setup

1. Install PostgreSQL and create database:

```sql
CREATE DATABASE yaytsa;
CREATE USER yaytsa WITH PASSWORD 'your-password';
GRANT ALL PRIVILEGES ON DATABASE yaytsa TO yaytsa;
```

1. Build the application:

```bash
mvn clean package
```

1. Run the application:

```bash
java -jar target/yaytsa-media-server-0.1.0-SNAPSHOT.jar
```

## Configuration

### Environment Variables

Key configuration options (see `.env.example` for full list):

```bash
# Database
DB_HOST=localhost
DB_PORT=5432
DB_NAME=yaytsa
DB_USER=yaytsa
DB_PASSWORD=changeme

# Server
SERVER_PORT=8080
LOG_LEVEL=INFO

# Media Library
LIBRARY_ROOTS=/path/to/music,/another/path
SCAN_THREADS=8

# Transcoding
TRANSCODE_ENABLED=true
FFMPEG_PATH=ffmpeg
MAX_CONCURRENT_TRANSCODES=4

# Security
BCRYPT_STRENGTH=12
CORS_ORIGINS=http://localhost:*
```

### Spring Profiles

- `dev` - Development with debug logging and auto-reload
- `prod` - Production with optimizations
- `test` - Testing with in-memory configuration

## Development

### Project Structure

```
server/
├── src/main/java/com/example/mediaserver/
│   ├── config/          # Spring configuration
│   ├── controller/      # REST API endpoints
│   ├── dto/            # Data transfer objects
│   ├── domain/         # Business logic
│   │   ├── model/      # Domain entities
│   │   ├── service/    # Business services
│   │   └── ports/      # Interface contracts
│   ├── infra/          # Infrastructure implementations
│   │   ├── persistence/# JPA repositories
│   │   ├── fs/         # File system operations
│   │   ├── transcoding/# FFmpeg integration
│   │   ├── images/     # Image processing
│   │   └── security/   # Auth & security
│   ├── mapper/         # Object mappers
│   └── error/          # Exception handling
├── src/main/resources/
│   ├── application.yml # Configuration
│   └── db/migration/   # Flyway migrations
└── src/test/           # Test suites
```

### Building from Source

```bash
# Clean build
mvn clean package

# Skip tests
mvn clean package -DskipTests

# Run tests only
mvn test

# Run integration tests
mvn verify
```

### Running Tests

```bash
# Unit tests
mvn test

# Integration tests with Testcontainers
mvn verify -Pintegration

# Specific test class
mvn test -Dtest=ItemServiceTest

# With coverage
mvn clean test jacoco:report
```

### Database Migrations

Flyway migrations are automatically applied on startup. To manage manually:

```bash
# Validate migrations
mvn flyway:validate

# Apply migrations
mvn flyway:migrate

# Clean database (CAUTION!)
mvn flyway:clean
```

## API Documentation

### OpenAPI/Swagger

When running in dev mode, access the interactive API documentation at:

- Swagger UI: <http://localhost:8080/swagger-ui.html>
- OpenAPI JSON: <http://localhost:8080/api-docs>

### Key Endpoints

#### Authentication

```http
POST /Users/AuthenticateByName
Content-Type: application/json

{
  "Username": "admin",
  "Pw": "password"
}
```

#### Get Library Items

```http
GET /Items?includeItemTypes=MusicAlbum&recursive=true&limit=50
Authorization: Bearer <token>
```

#### Stream Audio

```http
GET /Audio/{itemId}/stream?api_key=<token>&static=true
Range: bytes=0-1023
```

#### Report Playback

```http
POST /Sessions/Playing/Progress
Authorization: Bearer <token>
Content-Type: application/json

{
  "ItemId": "item-uuid",
  "PositionTicks": 100000000,
  "IsPaused": false
}
```

## Monitoring

### Health Checks

```bash
# Basic health
curl http://localhost:8080/manage/health

# Detailed health
curl http://localhost:8080/manage/health \
  -H "Authorization: Bearer <admin-token>"
```

### Metrics

Prometheus metrics available at:

```
http://localhost:8080/manage/prometheus
```

Key metrics:

- `http_server_requests` - HTTP request latencies
- `transcode_active_count` - Active transcodes
- `library_scan_duration` - Scan performance
- `cache_hit_ratio` - Cache effectiveness

## Performance Tuning

### JVM Options

```bash
# Virtual threads (enabled by default)
-XX:+EnableVirtualThreads

# Memory settings
-Xmx2g -Xms1g
-XX:MaxRAMPercentage=75.0

# GC tuning
-XX:+UseZGC
-XX:+ZGenerational
```

### Database Indexes

Critical indexes are created in the initial migration. Monitor slow queries:

```sql
-- Enable query logging
ALTER SYSTEM SET log_statement = 'all';
ALTER SYSTEM SET log_duration = 'on';
```

### Connection Pooling

Configure HikariCP in `application.yml`:

```yaml
spring.datasource.hikari:
  maximum-pool-size: 20
  minimum-idle: 5
  connection-timeout: 30000
```

## Security

### Authentication Flow

1. Client sends credentials to `/Users/AuthenticateByName`
2. Server validates password with BCrypt
3. Server generates 256-bit opaque token
4. Token stored in database with device binding
5. Client includes token in `Authorization` header or `api_key` param

### Best Practices

- Always use HTTPS in production
- Rotate tokens regularly
- Monitor failed authentication attempts
- Keep dependencies updated
- Follow OWASP guidelines

## Troubleshooting

### Common Issues

#### Database Connection Failed

```bash
# Check PostgreSQL is running
docker-compose ps postgres

# Test connection
psql -h localhost -U yaytsa -d yaytsa
```

#### FFmpeg Not Found

```bash
# Install FFmpeg
apt-get install ffmpeg  # Debian/Ubuntu
brew install ffmpeg      # macOS
```

#### Port Already in Use

```bash
# Change port in .env
SERVER_PORT=8081
```

#### Out of Memory

```bash
# Increase heap size
JAVA_OPTS_EXTRA="-Xmx4g -Xms2g"
```

### Debug Mode

Enable debug logging:

```bash
LOG_LEVEL=DEBUG
APP_LOG_LEVEL=TRACE
SQL_LOG_LEVEL=DEBUG
```

Remote debugging:

```bash
# Add to JAVA_OPTS_EXTRA
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests
5. Run `mvn verify`
6. Submit a pull request

## License

MIT License - see LICENSE file for details

## Roadmap

### Phase 1: Core Infrastructure ✅

- [x] Spring Boot skeleton with virtual threads
- [x] PostgreSQL schema and Flyway migrations
- [x] Stub controllers for all endpoints
- [x] Docker configuration

### Phase 2: Authentication & Users (In Progress)

- [ ] BCrypt password hashing
- [ ] Opaque token generation
- [ ] Device binding
- [ ] User management

### Phase 3: Library Scanning

- [ ] File system scanner
- [ ] jaudiotagger integration
- [ ] Incremental updates
- [ ] Watch service

### Phase 4: Direct Streaming

- [ ] Byte-range support
- [ ] ETag generation
- [ ] HEAD request handling

### Phase 5: Transcoding

- [ ] FFmpeg integration
- [ ] Process management
- [ ] Codec detection

### Phase 6: Sessions & Playback

- [ ] Session tracking
- [ ] Progress reporting
- [ ] Scrobbling

### Phase 7: Playlists

- [ ] CRUD operations
- [ ] Atomic reordering
- [ ] Sharing

### Phase 8: Images

- [ ] Artwork extraction
- [ ] Resizing & caching
- [ ] CDN support

### Phase 9: Production Hardening

- [ ] Performance optimization
- [ ] Security audit
- [ ] Documentation
- [ ] CI/CD pipeline

## Support

- GitHub Issues: <https://github.com/yourusername/yaytsa/issues>
- Documentation: <https://docs.yaytsa.io>
- Discord: <https://discord.gg/yaytsa>
