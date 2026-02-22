# Integration Tests - Behavior-Driven Development (BDD)

## Philosophy

These tests follow **Behavior-Driven Development (BDD)** principles, focusing on **WHAT the system does** (user-visible behavior) rather than **HOW it does it** (implementation details).

## Test Structure

### Directory Organization

```
tests/integration/
  fixtures/          # Test data and utilities
    data-factory.ts  # Discovers and prepares test data from media server library
    scenarios.ts     # Reusable Given-When-Then helpers
    mock-tracks.ts   # Mock data for queue tests (local logic)
  features/          # Feature-based test files
    authentication.feature.test.ts
    library-browsing.feature.test.ts
    queue-management.feature.test.ts
    playback.feature.test.ts
    favorites.feature.test.ts
    playlists.feature.test.ts
  setup.ts           # Test configuration and BDD utilities
```

## Writing BDD Tests

### Given-When-Then Format

All tests follow the **Given-When-Then** pattern:

✅ **Good**: `'Given: User viewing album, When: Clicks play album, Then: Queue contains all album tracks'`

❌ **Bad**: `'should set queue with tracks'`

### Focus on Behavior, Not Implementation

**✅ Test user-visible behavior:**

- Current track is playing
- User can skip to next track
- Queue has 5 tracks
- User is authenticated

**❌ Avoid testing implementation details:**

- `getCurrentIndex()` returns 1
- Token format is `/^[a-f0-9]{32}$/`
- `PositionTicks === 300000000`

## Running Tests

```bash
# Run all integration tests
npm run test:integration --workspace=@yay-tsa/core

# Run specific feature
npm run test:integration --workspace=@yay-tsa/core tests/integration/features/queue-management
```

## Configuration

Create `.env` file at project root:

```bash
YAYTSA_SERVER_URL=https://your-server.com
YAYTSA_TEST_USERNAME=your-username
YAYTSA_TEST_PASSWORD=your-password
```
