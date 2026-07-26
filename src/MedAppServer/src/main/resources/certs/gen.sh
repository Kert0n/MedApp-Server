#!/bin/sh
# Creates the local JWT signing key pair used by the dev profile.
#
# Writes to <project>/.local/secrets/, which is git-ignored and excluded from both the
# jar and the Docker build context: signing keys are a runtime input, never an artifact.
#
# Idempotent by default so a stray run cannot invalidate every token already issued.
# Pass --force to rotate deliberately.
#
# Only needed to run the app directly on the host: the Docker image generates its own
# pair at build time.
#
# Usage (from anywhere):  src/main/resources/certs/gen.sh [--force]
# Target directory:       SECRETS_DIR=/somewhere src/main/resources/certs/gen.sh

set -eu

# Resolve the project root from this script's own location, so the script works no matter
# what the current directory is.
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_root=$(CDPATH= cd -- "$script_dir/../../../.." && pwd)

# SECRETS_DIR lets the image build reuse this script instead of duplicating the openssl
# invocations; see Dockerfile.
secrets_dir="${SECRETS_DIR:-$project_root/.local/secrets}"

private_key="$secrets_dir/jwt-private.pem"
public_key="$secrets_dir/jwt-public.pem"

force=0
if [ "${1:-}" = "--force" ]; then
    force=1
fi

if [ -f "$private_key" ] && [ -f "$public_key" ] && [ "$force" -eq 0 ]; then
    echo "Key pair already present in $secrets_dir; nothing to do."
    echo "Pass --force to rotate (this invalidates every issued token)."
    exit 0
fi

mkdir -p "$secrets_dir"

tmp_keypair=$(mktemp "$secrets_dir/keypair.XXXXXX")
trap 'rm -f "$tmp_keypair"' EXIT INT TERM

openssl genrsa -out "$tmp_keypair" 4096
openssl rsa -in "$tmp_keypair" -pubout -out "$public_key"
openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt -in "$tmp_keypair" -out "$private_key"

chmod 600 "$private_key"
chmod 644 "$public_key"

echo "Wrote $private_key and $public_key"
