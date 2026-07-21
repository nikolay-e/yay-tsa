#!/usr/bin/env bash
# Bootstraps this project on a fresh Mac: Homebrew, XcodeGen, project generation,
# and a sanity check that Xcode CLI tools + a connected/simulated device are visible.
# Idempotent — safe to re-run.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

echo "== Xcode command line tools =="
if ! xcode-select -p >/dev/null 2>&1; then
  echo "Xcode command line tools not found. Run: xcode-select --install"
  echo "(This opens a GUI installer — needs a human click, can't be scripted.)"
  exit 1
fi
xcodebuild -version

echo "== Homebrew =="
if ! command -v brew >/dev/null 2>&1; then
  echo "Homebrew not found. Installing (this needs sudo + a password prompt once)..."
  /bin/bash -c "$(curl --proto '=https' --tlsv1.2 -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
  eval "$(/opt/homebrew/bin/brew shellenv 2>/dev/null || /usr/local/bin/brew shellenv)"
fi
brew --version

echo "== XcodeGen =="
if ! command -v xcodegen >/dev/null 2>&1; then
  brew install xcodegen
fi
xcodegen --version

echo "== Generating YayTsa.xcodeproj =="
xcodegen generate

echo "== Simulators available =="
xcrun simctl list devices available | grep -i iphone || true

echo "== Physical devices connected (plug in the test iPhone via cable first) =="
xcrun xctrace list devices 2>&1 | grep -v Simulator || true

echo
echo "Done. Next manual steps (need a human, can't be scripted):"
echo "  1. open YayTsa.xcodeproj"
echo "  2. Xcode > Settings > Accounts — sign in with an Apple ID (free personal team is fine)"
echo "  3. Select the YayTsa target > Signing & Capabilities > pick that team"
echo "  4. Plug in the iPhone, tap 'Trust This Computer' on the phone, select it as the run destination"
echo "  5. Cmd+R to build and run"
