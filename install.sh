#!/bin/sh
# Downloads and installs the apollo-mock CLI into ~/.apollo-mock/bin.
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/apollographql/apollo-mock/main/install.sh | sh
#
# Set APOLLO_MOCK_VERSION to install a specific release tag instead of the latest one.

set -eu

REPO="apollographql/apollo-mock"
INSTALL_DIR="$HOME/.apollo-mock"
BIN_DIR="$INSTALL_DIR/bin"

fail() {
  echo "error: $1" >&2
  exit 1
}

os=$(uname -s)
arch=$(uname -m)

case "$os" in
  Darwin)
    case "$arch" in
      arm64) asset="apollo-mock-macos-arm64" ;;
      *) fail "unsupported macOS architecture '$arch' (only arm64/Apple Silicon binaries are published)" ;;
    esac
    ;;
  Linux)
    case "$arch" in
      x86_64 | amd64) asset="apollo-mock-linux-x64" ;;
      *) fail "unsupported Linux architecture '$arch' (only x86_64 binaries are published)" ;;
    esac
    ;;
  *)
    fail "unsupported OS '$os' (only macOS and Linux binaries are published)"
    ;;
esac

version="${APOLLO_MOCK_VERSION:-}"
if [ -z "$version" ]; then
  version=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" | grep -m1 '"tag_name"' | sed -E 's/.*"tag_name": *"([^"]+)".*/\1/')
  [ -n "$version" ] || fail "could not determine the latest release, set APOLLO_MOCK_VERSION to install a specific tag"
fi

url="https://github.com/$REPO/releases/download/$version/$asset"

echo "Downloading $asset ($version)..."
mkdir -p "$BIN_DIR"
tmp_file=$(mktemp)
trap 'rm -f "$tmp_file"' EXIT

curl -fsSL "$url" -o "$tmp_file" || fail "failed to download $url"

mv "$tmp_file" "$BIN_DIR/apollo-mock"
chmod +x "$BIN_DIR/apollo-mock"

echo "Installed apollo-mock $version to $BIN_DIR/apollo-mock"

case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *)
    echo ""
    echo "Add it to your PATH by adding this line to your shell profile:"
    echo "  export PATH=\"$BIN_DIR:\$PATH\""
    ;;
esac
