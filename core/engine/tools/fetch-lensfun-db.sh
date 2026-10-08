#!/usr/bin/env bash
# Fetches the pinned lensfun lens/camera database (CC BY-SA 3.0, data only, no lensfun code) for
# lens::Database (lens_profile.h) and unpacks the XML files into $1 (default: ./lensfun-db).
# The archive is an immutable commit snapshot, checked against a SHA-256 before it is unpacked.
set -euo pipefail

COMMIT="bbd4332a9ec566fd9aa548c9e0d8ced238c56261"  # lensfun master, 2026-09-24
SHA256="fd0b47fbd71945da0ca89d62a3dacf9a8573c2a718301ec093dfab3b6d9a5331"
DEST="${1:-lensfun-db}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
curl -fsSL "https://github.com/lensfun/lensfun/archive/${COMMIT}.tar.gz" -o "$work/lensfun.tar.gz"
echo "${SHA256}  $work/lensfun.tar.gz" | sha256sum -c -
tar xzf "$work/lensfun.tar.gz" -C "$work"
mkdir -p "$DEST"
cp "$work/lensfun-${COMMIT}"/data/db/*.xml "$DEST"/
cp "$work/lensfun-${COMMIT}"/data/COPYING.CC_BY-SA_3.0 "$DEST"/
echo "lensfun database ${COMMIT}: $(ls "$DEST"/*.xml | wc -l) files in $DEST"
