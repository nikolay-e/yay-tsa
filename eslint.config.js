import js from '@eslint/js';
import typescript from '@typescript-eslint/eslint-plugin';
import typescriptParser from '@typescript-eslint/parser';
import svelte from 'eslint-plugin-svelte';
import svelteParser from 'svelte-eslint-parser';
import globals from 'globals';

export default [
  // Ignore patterns
  {
    ignores: [
      'node_modules/**',
      'dist/**',
      'build/**',
      '.svelte-kit/**',
      'apps/web/.svelte-kit/**',
      'packages/*/dist/**',
      'packages/*/build/**',
      '**/*.config.js',
      '**/*.config.ts',
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
          checksVoidReturn: { attributes: false }, // Allow promises in Svelte event handlers
        },
      ],
      '@typescript-eslint/await-thenable': 'error',
      '@typescript-eslint/promise-function-async': 'warn',
      '@typescript-eslint/require-await': 'error',
      'no-return-await': 'off', // Superseded by @typescript-eslint/return-await
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
    },
  },

  // Svelte files - with TypeScript support and accessibility
  {
    files: ['**/*.svelte'],
    languageOptions: {
      parser: svelteParser,
      parserOptions: {
        parser: typescriptParser,
        extraFileExtensions: ['.svelte'],
      },
      globals: {
        ...globals.browser,
      },
    },
    plugins: {
      svelte,
      '@typescript-eslint': typescript,
    },
    rules: {
      ...svelte.configs.recommended.rules,
      ...svelte.configs.prettier.rules,

      // Security rules for Svelte - CRITICAL for XSS prevention
      'svelte/no-at-html-tags': 'error', // Prevent XSS via @html
      'svelte/no-target-blank': 'error', // Security: require rel="noreferrer"

      // Component quality and best practices
      'svelte/button-has-type': 'warn',
      'svelte/no-unused-svelte-ignore': 'error',
      'svelte/no-unused-class-name': 'warn',
      'svelte/prefer-class-directive': 'warn',
      'svelte/prefer-style-directive': 'warn',
      'svelte/shorthand-attribute': 'warn',
      'svelte/shorthand-directive': 'warn',
      'svelte/spaced-html-comment': 'warn',

      // Note: Accessibility (a11y) rules are built into Svelte compiler
      // and are checked via svelte-check, not ESLint plugin

      // TypeScript rules for Svelte script blocks (non-type-aware only)
      // Note: Type checking for Svelte is handled by svelte-check
      '@typescript-eslint/no-unused-vars': [
        'warn',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
      '@typescript-eslint/no-explicit-any': 'warn',
      'no-console': ['warn', { allow: ['warn', 'error', 'info'] }],
      'prefer-const': 'error',
      'no-var': 'error',

      // Disable compiler warnings (handled by svelte-check)
      'svelte/valid-compile': 'off',
    },
  },
];
