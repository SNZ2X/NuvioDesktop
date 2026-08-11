#!/usr/bin/env bash
#
# Builds a portable (GOAMD64=v1, baseline x86-64) TorrServer binary and
# installs it as the bundled app resource at
#   composeApp/src/desktopMain/resources/torrserver/linux-amd64/TorrServer
#
# The official YouROK/TorrServer release binary is built with GOAMD64=v3
# (AVX-512) and refuses to start on older x86-64 CPUs (e.g. Ryzen 7 5700X,
# Zen 3), so we build from source with the baseline microarchitecture level.
#
# Pinned upstream: YouROK/TorrServer tag MatriX.142.2.
# The generated web UI (server/web/pages/template/*) is committed upstream, so
# no node/yarn is required: `go build ./cmd` from server/ is sufficient.
#
# Usage: ./dist/fetch-torrserver.sh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="MatriX.142.2"
SRC_URL="https://github.com/YouROK/TorrServer.git"
OUTPUT_DIR="${ROOT_DIR}/composeApp/src/desktopMain/resources/torrserver/linux-amd64"
OUTPUT="${OUTPUT_DIR}/TorrServer"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-torrserver.XXXXXX")"
trap 'rm -rf "${TMP}"' EXIT

command -v git >/dev/null || { echo "error: git is required" >&2; exit 1; }
command -v go >/dev/null || { echo "error: go is required (>= 1.25)" >&2; exit 1; }

echo "[nuvio-torrserver] cloning YouROK/TorrServer @ ${TAG}..."
git clone --depth 1 --branch "${TAG}" "${SRC_URL}" "${TMP}/TorrServer"

echo "[nuvio-torrserver] building server (GOAMD64=v1, baseline x86-64)..."
cd "${TMP}/TorrServer/server"
export GOAMD64=v1 CGO_ENABLED=0
go build -trimpath -o "${TMP}/TorrServer-linux-amd64" ./cmd

echo "[nuvio-torrserver] installing binary and license..."
install -Dm755 "${TMP}/TorrServer-linux-amd64" "${OUTPUT}"
# GPL-3.0 compliance: bundle the upstream license next to the binary
# (YouROK/TorrServer has no NOTICE file at MatriX.142.2).
install -Dm644 "${TMP}/TorrServer/LICENSE" "${OUTPUT_DIR}/LICENSE"

echo "[nuvio-torrserver] installed ${OUTPUT}"
sha256sum "${OUTPUT}"
