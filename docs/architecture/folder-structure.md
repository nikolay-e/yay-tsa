# Folder Structure

## Monorepo Topology

```
apps/
└── web/           # React 19 PWA frontend
packages/
├── core/          # Framework-agnostic business logic
└── platform/      # Platform-specific audio adapters
services/
├── server/        # Java Spring Boot backend
└── audio-separator/  # Python FastAPI for karaoke
docs/
└── architecture/  # Architecture documentation
```

**Deployment Separation:**

- `apps/` = Client/frontend deployables
- `services/` = Backend/ML deployables
- `packages/` = Shared libraries (not independently deployed)

## Web App (`apps/web/src/`)

```
src/
├── app/               # App shell, providers, global components
│   ├── App.tsx        # Root router configuration
│   ├── ui/            # Global UI components (Toast)
│   ├── routing/       # Routing infrastructure (ProtectedRoute)
│   └── infra/         # App infrastructure (ErrorBoundary)
├── pages/             # Route-level screens (one per route)
├── features/          # Domain feature slices
│   ├── auth/
│   ├── library/
│   ├── navigation/
│   └── player/
└── shared/            # Cross-feature utilities
    ├── utils/
    └── testing/
```

## Import Boundaries

### Layer Hierarchy

```
┌─────────────────────────────────────┐
│              app/                   │  ← Can import from all layers
├─────────────────────────────────────┤
│             pages/                  │  ← Can import from features, shared
├─────────────────────────────────────┤
│           features/                 │  ← Can import from shared, other features
├─────────────────────────────────────┤
│            shared/                  │  ← Can only import from shared
└─────────────────────────────────────┘
```

### Rules (Enforced by ESLint)

| Layer       | Can Import From              |
| ----------- | ---------------------------- |
| `app/`      | shared, features, pages, app |
| `pages/`    | shared, features             |
| `features/` | shared, other features       |
| `shared/`   | shared only                  |

**Cross-Package Rules:**

- `@yay-tsa/core` is standalone (no external package imports)
- `@yay-tsa/platform` can import from `@yay-tsa/core`
- Packages NEVER import from apps

### Enforced Restrictions

1. **No deep relative imports**: `../../../` banned
2. **No circular dependencies**: `import/no-cycle` enabled
3. **Case-sensitive imports**: Prevents Linux CI failures
4. **Public API only**: Features accessed via `index.ts`

## Feature Module Structure

Every feature follows this shape:

```
features/<name>/
├── components/       # UI components
│   └── index.ts      # Barrel export
├── hooks/            # React hooks
│   └── index.ts      # Barrel export
├── stores/           # Zustand stores (state management)
└── index.ts          # PUBLIC API (consumers import only this)
```

**Rules:**

- State folder is always `stores/` (not `state/` or `model/`)
- Import features ONLY through `index.ts`:

  ```typescript
  // ✅ Correct
  import { useAuthStore } from '@/features/auth';

  // ❌ Wrong
  import { useAuthStore } from '@/features/auth/stores/auth.store';
  ```

## Path Aliases

```typescript
// Available in tsconfig.json + vite.config.ts
@/*        → ./src/*
@app/*     → ./src/app/*
@pages/*   → ./src/pages/*
@features/* → ./src/features/*
@shared/*  → ./src/shared/*
```

## Naming Conventions

| Type             | Convention                | Example              |
| ---------------- | ------------------------- | -------------------- |
| Directories      | kebab-case                | `user-profile/`      |
| React components | PascalCase.tsx            | `PlayerBar.tsx`      |
| Non-component TS | kebab-case.ts             | `session-manager.ts` |
| Hooks            | camelCase (use prefix)    | `useAlbums.ts`       |
| Stores           | kebab-case.store.ts       | `player.store.ts`    |
| Tests            | `*.spec.ts` / `*.test.ts` | `auth.spec.ts`       |

## Barrel Exports Policy

### Strategy: Barrels only at module boundaries

- ✅ `features/<name>/index.ts` - feature public API
- ✅ `shared/index.ts` - shared utilities public API
- ✅ `app/ui/index.ts`, `app/routing/index.ts` - app submodules
- ❌ Deep nested barrels (e.g., `features/auth/stores/index.ts`)

## Package Public API

Packages use `exports` in `package.json` to enforce boundaries:

```json
{
  "exports": {
    ".": {
      "types": "./dist/index.d.ts",
      "import": "./dist/index.js"
    }
  }
}
```

This prevents:

```typescript
// ❌ Blocked by exports field
import { internal } from '@yay-tsa/core/src/internal/helper';
```

## Empty Folders Policy

Empty feature subfolders (`components/`, `hooks/`, `stores/`) should be:

- Kept with `.gitkeep` if scaffolding is mandatory
- OR deleted if not used

**Current approach**: Keep `.gitkeep` for consistency.

## CI Enforcement

All rules are enforced in CI via:

- `npm run type-check` - TypeScript compilation
- `npm run lint` - ESLint with boundaries, no-cycle, case-sensitivity
- Pre-commit hooks via `pre-commit`
