#!/bin/sh
# Docker entrypoint script for Yaytsa Media Server

set -e

echo "Starting Yaytsa Media Server..."
echo "Java version:"
java -version

# Wait for PostgreSQL to be ready
if [ -n "$DB_HOST" ]; then
  echo "Waiting for PostgreSQL at $DB_HOST:${DB_PORT:-5432}..."

  MAX_RETRIES=30
  RETRY_COUNT=0

  while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
    if nc -z "$DB_HOST" "${DB_PORT:-5432}" 2>/dev/null; then
      echo "PostgreSQL is ready!"
      break
    fi

    RETRY_COUNT=$((RETRY_COUNT + 1))
    echo "Waiting for PostgreSQL... ($RETRY_COUNT/$MAX_RETRIES)"
    sleep 2
  done

  if [ $RETRY_COUNT -eq $MAX_RETRIES ]; then
    echo "ERROR: PostgreSQL is not available after $MAX_RETRIES attempts"
    exit 1
  fi
fi

# Validate required environment variables
if [ -z "$DB_HOST" ] || [ -z "$DB_USER" ] || [ -z "$DB_PASSWORD" ]; then
  echo "ERROR: Required database environment variables are not set"
  echo "Please set: DB_HOST, DB_USER, DB_PASSWORD"
  exit 1
fi

# Check if media library path exists
if [ -n "$LIBRARY_ROOTS" ]; then
  echo "Checking media library paths: $LIBRARY_ROOTS"
  OLD_IFS="$IFS"
  IFS=','
  for path in $LIBRARY_ROOTS; do
    # Security: Validate path to prevent directory traversal
    # Reject paths containing .. (path traversal attempt)
    case "$path" in
    *..*)
      echo "ERROR: Invalid path containing '..': $path"
      echo "Path traversal sequences are not allowed for security reasons"
      exit 1
      ;;
    esac

    # Ensure path is absolute (starts with /)
    case "$path" in
    /*)
      # Path is absolute, continue
      ;;
    *)
      echo "ERROR: LIBRARY_ROOTS must contain absolute paths: $path"
      echo "Relative paths like './' or '../' are not allowed"
      exit 1
      ;;
    esac

    if [ ! -d "$path" ]; then
      echo "WARNING: Media library path does not exist: $path"
      echo "Creating directory: $path"
      mkdir -p "$path"
    else
      echo "Media library path found: $path"
    fi
  done
  IFS="$OLD_IFS"
fi

# Check FFmpeg installation
if command -v ffmpeg >/dev/null 2>&1; then
  echo "FFmpeg version:"
  ffmpeg -version | head -n1
else
  echo "WARNING: FFmpeg not found. Transcoding will not be available."
fi

# Create necessary directories if they don't exist
mkdir -p /app/logs /app/temp/transcode /app/temp/images

# Set JVM heap size based on container memory if not explicitly set
if [ -z "$JAVA_OPTS_HEAP" ]; then
  CONTAINER_MEM_LIMIT=""

  # Try cgroup v2 first (modern Linux/K8s)
  if [ -f /sys/fs/cgroup/memory.max ]; then
    MEM_MAX=$(cat /sys/fs/cgroup/memory.max)
    if [ "$MEM_MAX" != "max" ]; then
      CONTAINER_MEM_LIMIT=$MEM_MAX
    fi
  # Fall back to cgroup v1
  elif [ -f /sys/fs/cgroup/memory/memory.limit_in_bytes ]; then
    CONTAINER_MEM_LIMIT=$(cat /sys/fs/cgroup/memory/memory.limit_in_bytes)
    # Check if limit is the max value (no limit set)
    if [ "$CONTAINER_MEM_LIMIT" -ge 9223372036854775807 ]; then
      CONTAINER_MEM_LIMIT=""
    fi
  fi

  if [ -n "$CONTAINER_MEM_LIMIT" ]; then
    # Use 75% of container memory for heap
    HEAP_SIZE=$((CONTAINER_MEM_LIMIT * 75 / 100 / 1024 / 1024))
    export JAVA_OPTS_HEAP="-Xmx${HEAP_SIZE}m -Xms$((HEAP_SIZE / 2))m"
    echo "Container memory limit detected. Setting heap size: $JAVA_OPTS_HEAP"
  fi
fi

# Combine all Java options (use eval to properly split arguments)
echo "Starting application with Java options: $JAVA_OPTS $JAVA_OPTS_HEAP $JAVA_OPTS_EXTRA"
echo "Active Spring profile: ${SPRING_PROFILES_ACTIVE:-default}"
echo "Server port: ${SERVER_PORT:-8096}"

# Start the application (unquoted variables for natural word-splitting, no eval to prevent command injection)
# shellcheck disable=SC2086 # Intentional word-splitting for JVM options
exec java $JAVA_OPTS $JAVA_OPTS_HEAP $JAVA_OPTS_EXTRA -jar /app/app.jar "$@"
