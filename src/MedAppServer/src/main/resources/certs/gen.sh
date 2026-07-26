#!/bin/sh
# Creates the JWT signing key pair next to this script, as certs/private.pem and
# certs/public.pem — the location the application reads via classpath:certs/.
#
# Idempotent: if both files already exist it does nothing. Keys that are there on purpose
# stay untouched, so the image build can just run this unconditionally. Pass --force to
# rotate deliberately; that invalidates every token already issued.
#
# Usage (from anywhere): src/main/resources/certs/gen.sh [--force]

set -eu

# Resolve from this script's own location so the working directory does not matter.
certs_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

private_key="$certs_dir/private.pem"
public_key="$certs_dir/public.pem"

force=0
if [ "${1:-}" = "--force" ]; then
    force=1
fi

if [ -f "$private_key" ] && [ -f "$public_key" ] && [ "$force" -eq 0 ]; then
    echo "Key pair already present in $certs_dir; leaving it alone."
    exit 0
fi

tmp_keypair=$(mktemp "$certs_dir/keypair.XXXXXX")
trap 'rm -f "$tmp_keypair"' EXIT INT TERM

openssl genrsa -out "$tmp_keypair" 4096
openssl rsa -in "$tmp_keypair" -pubout -out "$public_key"
openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt -in "$tmp_keypair" -out "$private_key"

chmod 600 "$private_key"
chmod 644 "$public_key"

echo "Wrote $private_key and $public_key"
