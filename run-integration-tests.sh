#!/bin/bash
# Integration Test Runner
# Runs end-to-end tests against real Jellyfin server using Docker Compose

set -e  # Exit on error

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${BLUE}🧪 Jellyfin Mini Client - Integration Tests${NC}"
echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""

# Check if .env file exists
if [ ! -f .env ]; then
    echo -e "${RED}✗ Error: .env file not found${NC}"
    echo ""
    echo "Please create a .env file:"
    echo "  cp .env.example .env"
    echo ""
    exit 1
fi

# Load environment variables
set -a
source .env
set +a

echo -e "${GREEN}✓ Configuration loaded${NC}"
if [ -n "$JELLYFIN_API_KEY" ]; then
    echo -e "  API Key: ${JELLYFIN_API_KEY:0:8}..."
fi
echo -e "  Server: ${JELLYFIN_SERVER_URL:-http://jellyfin-server:8096}"
echo ""

# Check if Docker is running
if ! docker info > /dev/null 2>&1; then
    echo -e "${RED}✗ Error: Docker is not running${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Docker is running${NC}"
echo ""

# Parse command line arguments
MODE="${1:-full}"

case "$MODE" in
    full)
        echo -e "${BLUE}Running full integration test suite...${NC}"
        FILTER=""
        ;;
    auth)
        echo -e "${BLUE}Running authentication tests only...${NC}"
        FILTER="auth.integration.test.ts"
        ;;
    items)
        echo -e "${BLUE}Running library items tests only...${NC}"
        FILTER="items.integration.test.ts"
        ;;
    queue)
        echo -e "${BLUE}Running playback queue tests only...${NC}"
        FILTER="queue.integration.test.ts"
        ;;
    state)
        echo -e "${BLUE}Running playback state tests only...${NC}"
        FILTER="state.integration.test.ts"
        ;;
    *)
        echo -e "${RED}✗ Invalid mode: $MODE${NC}"
        echo ""
        echo "Usage: $0 [mode]"
        echo ""
        echo "Modes:"
        echo "  full   - Run all integration tests (default)"
        echo "  auth   - Run authentication tests only"
        echo "  items  - Run library items tests only"
        echo "  queue  - Run playback queue tests only"
        echo "  state  - Run playback state tests only"
        echo ""
        exit 1
        ;;
esac

echo ""

# Build and run tests
echo -e "${BLUE}Building test container...${NC}"

if [ -n "$FILTER" ]; then
    # Run specific test file
    docker-compose -f docker-compose.test.yml run --rm \
        integration-tests \
        npm run test:integration -- "$FILTER"
else
    # Run all tests
    docker-compose -f docker-compose.test.yml run --rm \
        integration-tests
fi

TEST_EXIT_CODE=$?

echo ""
echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"

if [ $TEST_EXIT_CODE -eq 0 ]; then
    echo -e "${GREEN}✓ All integration tests passed!${NC}"
else
    echo -e "${RED}✗ Some integration tests failed${NC}"
fi

echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""

# Cleanup
echo -e "${BLUE}Cleaning up...${NC}"
docker-compose -f docker-compose.test.yml down

exit $TEST_EXIT_CODE
