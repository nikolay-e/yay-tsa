import js from '@eslint/js';
import typescript from '@typescript-eslint/eslint-plugin';
import typescriptParser from '@typescript-eslint/parser';
import react from 'eslint-plugin-react';
import reactHooks from 'eslint-plugin-react-hooks';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import boundaries from 'eslint-plugin-boundaries';
import importPlugin from 'eslint-plugin-import';
import globals from 'globals';

export default [
  // Ignore patterns
  {
    ignores: [
      'node_modules/**',
      'dist/**',
      'build/**',
      'packages/*/dist/**',
      'packages/*/build/**',
      '**/*.config.js',
      '**/*.config.ts',
      '**/tests/**',
      '**/*.test.ts',
      '**/*.spec.ts',
    ],
  },

  // JavaScript/TypeScript files (non-TS project files)
  {
    files: ['**/*.js'],
    languageOptions: {
      ecmaVersion: 2020,
      sourceType: 'module',
      globals: {
        ...globals.browser,
        ...globals.node,
        ...globals.es2020,
      },
    },
    rules: {
      ...js.configs.recommended.rules,
      'no-console': ['warn', { allow: ['warn', 'error'] }],
      'no-debugger': 'error',
      'no-var': 'error',
      'prefer-const': 'error',
      'no-eval': 'error',
      'no-implied-eval': 'error',
    },
  },

  // TypeScript files - with type checking
  {
    files: ['packages/*/src/**/*.ts', 'apps/*/src/**/*.ts'],
    languageOptions: {
      ecmaVersion: 2020,
      sourceType: 'module',
      parser: typescriptParser,
      parserOptions: {
        project: [
          './packages/core/tsconfig.json',
          './packages/platform/tsconfig.json',
          './apps/web/tsconfig.json',
        ],
        tsconfigRootDir: import.meta.dirname,
      },
      globals: {
        ...globals.browser,
        ...globals.node,
        ...globals.es2020,
      },
    },
    plugins: {
      '@typescript-eslint': typescript,
    },
    rules: {
      ...js.configs.recommended.rules,
      ...typescript.configs.recommended.rules,

      // TypeScript-specific rules for quality
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
      '@typescript-eslint/no-explicit-any': 'warn',
      '@typescript-eslint/explicit-function-return-type': 'off',
      '@typescript-eslint/explicit-module-boundary-types': 'off',

      // Strict type safety rules - enforce proper typing
      '@typescript-eslint/no-unsafe-assignment': 'error',
      '@typescript-eslint/no-unsafe-member-access': 'error',
      '@typescript-eslint/no-unsafe-argument': 'error',
      '@typescript-eslint/no-unsafe-call': 'error',
      '@typescript-eslint/no-unsafe-return': 'error',

      // Async/Promise rules - CRITICAL for API-heavy codebase
      '@typescript-eslint/no-floating-promises': 'error',
      '@typescript-eslint/no-misused-promises': [
        'error',
        {
          checksVoidReturn: { attributes: false },
        },
      ],
      '@typescript-eslint/await-thenable': 'error',
      '@typescript-eslint/promise-function-async': 'warn',
      '@typescript-eslint/require-await': 'error',
      'no-return-await': 'off',
      '@typescript-eslint/return-await': ['error', 'in-try-catch'],

      // Type safety improvements
      '@typescript-eslint/no-unnecessary-type-assertion': 'warn',
      '@typescript-eslint/prefer-nullish-coalescing': 'warn',
      '@typescript-eslint/prefer-optional-chain': 'warn',
      '@typescript-eslint/no-non-null-assertion': 'warn',

      // Code quality rules
      'no-console': ['warn', { allow: ['warn', 'error', 'info'] }],
      'no-debugger': 'error',
      'no-alert': 'error',
      eqeqeq: ['error', 'always', { null: 'ignore' }],
      'no-var': 'error',
      'prefer-const': 'error',
      'prefer-arrow-callback': 'error',
      'prefer-template': 'warn',
      'object-shorthand': 'warn',

      // Async/Promise rules
      'no-promise-executor-return': 'error',
      'prefer-promise-reject-errors': 'error',

      // Security rules - CRITICAL for auth/API project
      'no-eval': 'error',
      'no-implied-eval': 'error',
      'no-new-func': 'error',
      'no-script-url': 'error',

      // Ban deep relative imports (max 2 levels up)
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['../../../*'],
              message:
                'Use path aliases (@/, @app/, @features/, @shared/, @pages/) instead of deep relative imports.',
            },
          ],
        },
      ],
    },
  },

  // Import cycle detection and case-sensitivity for all TS/TSX files
  {
    files: ['packages/*/src/**/*.ts', 'apps/*/src/**/*.ts', 'apps/*/src/**/*.tsx'],
    plugins: {
      import: importPlugin,
    },
    settings: {
      'import/resolver': {
        typescript: {
          alwaysTryTypes: true,
          project: [
            './packages/core/tsconfig.json',
            './packages/platform/tsconfig.json',
            './apps/web/tsconfig.json',
          ],
        },
      },
    },
    rules: {
      // Detect circular dependencies
      'import/no-cycle': ['error', { maxDepth: 10, ignoreExternal: true }],
      // Case-sensitive imports (critical for Linux CI)
      'import/no-unresolved': ['error', { caseSensitive: true }],
      // Enforce named exports order
      'import/order': [
        'warn',
        {
          groups: ['builtin', 'external', 'internal', 'parent', 'sibling', 'index'],
          'newlines-between': 'never',
        },
      ],
    },
  },

  // React/TSX files - with accessibility and hooks rules
  {
    files: ['apps/web/src/**/*.tsx'],
    languageOptions: {
      ecmaVersion: 2020,
      sourceType: 'module',
      parser: typescriptParser,
      parserOptions: {
        project: ['./apps/web/tsconfig.json'],
        tsconfigRootDir: import.meta.dirname,
        ecmaFeatures: {
          jsx: true,
        },
      },
      globals: {
        ...globals.browser,
      },
    },
    plugins: {
      react,
      'react-hooks': reactHooks,
      'jsx-a11y': jsxA11y,
      '@typescript-eslint': typescript,
    },
    settings: {
      react: {
        version: 'detect',
      },
    },
    rules: {
      ...js.configs.recommended.rules,
      ...typescript.configs.recommended.rules,
      ...react.configs.recommended.rules,
      ...react.configs['jsx-runtime'].rules,
      ...jsxA11y.configs.recommended.rules,

      // React Hooks rules - CRITICAL
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn',

      // React best practices
      'react/prop-types': 'off',
      'react/jsx-no-target-blank': 'error',
      'react/jsx-key': 'error',
      'react/no-unescaped-entities': 'warn',
      'react/self-closing-comp': 'warn',
      'react/jsx-curly-brace-presence': ['warn', { props: 'never', children: 'never' }],

      // Accessibility
      'jsx-a11y/alt-text': 'error',
      'jsx-a11y/anchor-is-valid': 'error',
      'jsx-a11y/click-events-have-key-events': 'warn',
      'jsx-a11y/no-static-element-interactions': 'warn',

      // TypeScript in React
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
      '@typescript-eslint/no-explicit-any': 'warn',

      // Code quality
      'no-console': ['warn', { allow: ['warn', 'error', 'info'] }],
      'prefer-const': 'error',
      'no-var': 'error',
    },
  },

  // Import boundaries for web app
  {
    files: ['apps/web/src/**/*.ts', 'apps/web/src/**/*.tsx'],
    plugins: {
      boundaries,
    },
    settings: {
      'boundaries/include': ['apps/web/src/**/*'],
      'boundaries/elements': [
        { type: 'app', pattern: 'apps/web/src/app/**/*' },
        { type: 'pages', pattern: 'apps/web/src/pages/**/*' },
        { type: 'features', pattern: 'apps/web/src/features/**/*' },
        { type: 'shared', pattern: 'apps/web/src/shared/**/*' },
      ],
    },
    rules: {
      'boundaries/element-types': [
        'error',
        {
          default: 'disallow',
          rules: [
            // shared: can only import from shared (no features, pages, app)
            { from: 'shared', allow: ['shared'] },
            // features: can import from shared and other features (not pages or app)
            { from: 'features', allow: ['shared', 'features'] },
            // pages: can import from features and shared (not app)
            { from: 'pages', allow: ['shared', 'features'] },
            // app: can import from anything within the app
            { from: 'app', allow: ['shared', 'features', 'pages', 'app'] },
          ],
        },
      ],
      'boundaries/no-private': 'error',
    },
  },

  // Cross-package import boundaries
  {
    files: ['packages/*/src/**/*.ts'],
    plugins: {
      boundaries,
    },
    settings: {
      'boundaries/include': ['packages/*/src/**/*', 'apps/*/src/**/*'],
      'boundaries/elements': [
        { type: 'core', pattern: 'packages/core/src/**/*' },
        { type: 'platform', pattern: 'packages/platform/src/**/*' },
        { type: 'web', pattern: 'apps/web/src/**/*' },
      ],
    },
    rules: {
      'boundaries/element-types': [
        'error',
        {
          default: 'disallow',
          rules: [
            // core: standalone, no dependencies on other packages
            { from: 'core', allow: ['core'] },
            // platform: can import from core
            { from: 'platform', allow: ['core', 'platform'] },
            // packages cannot import from apps
          ],
        },
      ],
    },
  },
];
