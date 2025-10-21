#!/bin/bash
# Setup pre-commit hooks
# This script installs pre-commit if needed and sets up the hooks

set -e

echo "Setting up pre-commit hooks..."

# Check if pre-commit is installed
if ! command -v pre-commit &> /dev/null; then
    echo "pre-commit not found. Installing..."

    # Try different installation methods
    if command -v pip3 &> /dev/null; then
        pip3 install pre-commit
    elif command -v pip &> /dev/null; then
        pip install pre-commit
    elif command -v brew &> /dev/null; then
        brew install pre-commit
    else
        echo "Error: Could not install pre-commit. Please install manually:"
        echo "  pip install pre-commit"
        echo "  or visit: https://pre-commit.com/#install"
        exit 1
    fi
fi

# Install the git hooks
echo "Installing git hooks..."
pre-commit install --install-hooks

# Install pre-push hooks
pre-commit install --hook-type pre-push

echo "✓ Pre-commit hooks installed successfully!"
echo ""
echo "To run hooks manually:"
echo "  pre-commit run --all-files"
echo ""
echo "To skip hooks temporarily:"
echo "  git commit --no-verify"
