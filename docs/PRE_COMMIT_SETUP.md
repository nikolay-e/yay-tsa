# Pre-commit Setup Guide

This project uses [pre-commit](https://pre-commit.com/) to ensure code quality and consistency before commits.

## What Pre-commit Does

Pre-commit automatically runs checks on your code before you commit:

- ✅ **Code Formatting** - Prettier for consistent code style
- ✅ **Type Checking** - TypeScript type validation
- ✅ **Linting** - Markdown and shell script linting
- ✅ **File Checks** - No trailing whitespace, correct line endings
- ✅ **Security** - Detect private keys, check for large files
- ✅ **Tests** - Run unit tests on pre-push (optional)

## Installation

### Option 1: Automatic Setup (Recommended)

Run the setup script:

```bash
./scripts/setup-pre-commit.sh
```

### Option 2: Manual Installation

1. **Install pre-commit:**

   ```bash
   # Using pip
   pip install pre-commit

   # Or using Homebrew (macOS)
   brew install pre-commit
   ```

2. **Install the git hooks:**

   ```bash
   pre-commit install
   pre-commit install --hook-type pre-push
   ```

3. **Install dependencies:**

   ```bash
   npm install
   cd packages/core && npm install
   ```

## Usage

### Automatic (Recommended)

Pre-commit hooks run automatically when you commit:

```bash
git add .
git commit -m "Your commit message"
# Hooks run automatically here
```

### Manual Run

Run hooks on all files:

```bash
pre-commit run --all-files
```

Run hooks on staged files only:

```bash
pre-commit run
```

Run specific hook:

```bash
pre-commit run prettier --all-files
pre-commit run tsc --all-files
```

### Skipping Hooks (Use Sparingly)

If you need to bypass hooks temporarily:

```bash
git commit --no-verify -m "Emergency fix"
```

**Warning:** Only use `--no-verify` for urgent fixes. The CI will still run all checks.

## What Gets Checked

### On Every Commit

1. **File Quality**
   - Remove trailing whitespace
   - Fix end of file
   - Check YAML/JSON syntax
   - Detect large files (>1MB)
   - Detect merge conflicts

2. **Code Formatting**
   - Prettier formats TypeScript, JavaScript, JSON, YAML, Markdown
   - Auto-fixes formatting issues

3. **TypeScript**
   - Type checking (no emit)
   - Ensures code compiles

4. **Markdown**
   - Linting with markdownlint
   - Auto-fixes formatting issues

5. **Shell Scripts**
   - ShellCheck linting
   - Validates bash syntax

### On Pre-push (Optional)

- Unit tests (`npm test`)
- Takes longer, only runs before pushing

## Configuration Files

- `.pre-commit-config.yaml` - Pre-commit hook configuration
- `.prettierrc` - Prettier formatting rules
- `.prettierignore` - Files to exclude from Prettier
- `.markdownlint.yaml` - Markdown linting rules

## Troubleshooting

### Hooks Fail on Commit

If hooks fail:

1. **Review the errors** - Pre-commit shows what failed
2. **Let it auto-fix** - Many hooks auto-fix issues
3. **Manually fix** - Fix remaining issues
4. **Re-stage and commit**:
   ```bash
   git add .
   git commit -m "Your message"
   ```

### Pre-commit Not Running

Reinstall hooks:

```bash
pre-commit install --install-hooks
```

### Update Hooks

Update to latest versions:

```bash
pre-commit autoupdate
```

### Clear Cache

If hooks behave strangely:

```bash
pre-commit clean
pre-commit install --install-hooks
```

## CI Integration

Pre-commit checks also run in CI:

- **GitHub Actions** runs `pre-commit run --all-files`
- All jobs depend on pre-commit passing
- Ensures consistent code quality

## Customization

### Skip Specific Hooks

In `.pre-commit-config.yaml`, add `exclude` pattern:

```yaml
- id: prettier
  exclude: ^legacy/
```

### Disable a Hook

Comment out or remove from `.pre-commit-config.yaml`

### Add Custom Hook

Add to `repos` in `.pre-commit-config.yaml`:

```yaml
- repo: local
  hooks:
    - id: your-custom-hook
      name: Your Hook Name
      entry: your-command
      language: system
```

## Benefits

- 🚀 **Catch Issues Early** - Before they reach CI
- 🎨 **Consistent Style** - Automatic formatting
- 🔒 **Security** - Detect secrets before commit
- ⚡ **Faster CI** - Less likely to fail
- 👥 **Team Consistency** - Everyone uses same checks

## Learn More

- [Pre-commit Documentation](https://pre-commit.com/)
- [Available Hooks](https://pre-commit.com/hooks.html)
- [Writing Custom Hooks](https://pre-commit.com/#new-hooks)
