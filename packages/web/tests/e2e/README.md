# E2E Tests - Yaytsa Music Client

Playwright E2E тесты для веб-приложения Yaytsa.

## Setup

1. Install dependencies:

```bash
npm install
```

1. Install Playwright browsers:

```bash
npx playwright install
```

1. Create `.env` file from template:

```bash
cp .env.example .env
```

1. Update `.env` with your server credentials:

```
YAYTSA_SERVER_URL=https://your-server.com
YAYTSA_TEST_USERNAME=your-username
YAYTSA_TEST_PASSWORD=your-password
```

## Running Tests

### Run all tests (headless)

```bash
npm run test:e2e
```

### Run tests with UI mode (interactive)

```bash
npm run test:e2e:ui
```

### Run tests in headed mode (see browser)

```bash
npm run test:e2e:headed
```

### Debug tests

```bash
npm run test:e2e:debug
```

### View test report

```bash
npm run test:e2e:report
```

### Run specific test file

```bash
npx playwright test auth.spec.ts
```

### Run tests on specific browser

```bash
npx playwright test --project=chromium
npx playwright test --project=mobile
```

## Test Structure

```
tests/e2e/
├── fixtures/           # Test fixtures (authenticated page)
│   └── auth.fixture.ts
├── helpers/            # Test utilities
│   └── test-utils.ts
├── pages/              # Page Object Models
│   ├── LoginPage.ts
│   ├── LibraryPage.ts
│   ├── AlbumPage.ts
│   └── PlayerBar.ts
└── *.spec.ts           # Test files
```

## Test Coverage

### Authentication (auth.spec.ts)

- Login/logout flow
- Form validation
- Session persistence
- Invalid credentials handling

### Library Browsing (library.spec.ts)

- Album grid display
- Album details navigation
- Pagination
- Metadata display

### Playback (playback.spec.ts)

- Play/pause controls
- Skip next/previous
- Volume control
- Seek functionality
- Player bar visibility

### Queue Management (queue.spec.ts)

- Queue creation from album
- Shuffle mode
- Repeat modes (off/all/one)
- Track navigation
- Queue persistence

### Search (search.spec.ts)

- Search functionality
- Partial matches
- No results handling
- Case-insensitive search
- Search result navigation

### Responsive UI (responsive.spec.ts)

- Mobile viewport (390x844)
- Desktop viewport (1920x1080)
- Tablet viewport (768x1024)
- Touch interactions
- Bottom navigation

## Best Practices

1. **Use Page Objects**: All page interactions should go through Page Object Models
2. **Use Fixtures**: Use `authenticatedPage` fixture for tests requiring login
3. **Wait for Elements**: Always use `waitFor*` methods instead of arbitrary timeouts
4. **Data-testid**: Prefer `data-testid` selectors for stability
5. **Cleanup**: Tests should be independent and not rely on previous test state

## CI/CD Integration

Tests are configured to run in CI with:

- 2 retries on failure
- 1 worker (sequential execution)
- Screenshots on failure
- Video recording on failure

## Debugging

### View trace

After test failure, traces are automatically recorded:

```bash
npx playwright show-trace trace.zip
```

### Open Playwright Inspector

```bash
npx playwright test --debug
```

### Generate code

Record interactions to generate test code:

```bash
npx playwright codegen http://localhost:5173
```

## Environment Variables

- `YAYTSA_SERVER_URL` - Media server URL
- `YAYTSA_TEST_USERNAME` - Test user username
- `YAYTSA_TEST_PASSWORD` - Test user password
- `BASE_URL` - Application base URL (default: <http://localhost:5173>)
- `CI` - Set to true in CI environment

## Writing New Tests

1. Create new spec file: `tests/e2e/your-feature.spec.ts`
2. Import fixtures and page objects
3. Use `test.describe()` for grouping
4. Use `test.beforeEach()` for common setup
5. Write descriptive test names
6. Use assertions from `@playwright/test`

Example:

```typescript
import { test, expect } from './fixtures/auth.fixture';
import { YourPage } from './pages/YourPage';

test.describe('Your Feature', () => {
  let yourPage: YourPage;

  test.beforeEach(async ({ authenticatedPage }) => {
    yourPage = new YourPage(authenticatedPage);
    await yourPage.goto();
  });

  test('should do something', async () => {
    await yourPage.doSomething();
    await expect(yourPage.someElement).toBeVisible();
  });
});
```
