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
    export_line="export PATH=\"$BIN_DIR:\$PATH\""
    rc_file=""

    case "${SHELL:-}" in
      */zsh) rc_file="${ZDOTDIR:-$HOME}/.zshrc" ;;
      */bash)
        if [ "$os" = "Darwin" ] && [ -f "$HOME/.bash_profile" ]; then
          rc_file="$HOME/.bash_profile"
        else
          rc_file="$HOME/.bashrc"
        fi
        ;;
    esac

    if [ -n "$rc_file" ]; then
      if [ -f "$rc_file" ] && grep -qF "$export_line" "$rc_file" 2>/dev/null; then
        : # already added
      else
        printf '\n# Added by apollo-mock installer\n%s\n' "$export_line" >> "$rc_file"
        echo ""
        echo "Added apollo-mock to your PATH in $rc_file"
      fi
      echo "Run 'export PATH=\"$BIN_DIR:\$PATH\"' to use it now, or open a new shell."
    else
      echo ""
      echo "Add it to your PATH by adding this line to your shell profile:"
      echo "  $export_line"
    fi
    ;;
esac
