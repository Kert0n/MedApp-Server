#!/bin/sh
set -eu

umask 077
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_dir=$(CDPATH= cd -- "$script_dir/../../../.." && pwd)

force=false
if [ "${1:-}" = "--force" ]; then
    force=true
    shift
fi
if [ "$#" -gt 1 ]; then
    echo "Usage: $0 [--force] [output-directory]" >&2
    exit 2
fi

output_dir=${1:-"$project_dir/.local/secrets"}
private_key="$output_dir/jwt-private.pem"
public_key="$output_dir/jwt-public.pem"
temporary_dir=""

mkdir -p "$output_dir"
chmod 700 "$output_dir"
trap 'if [ -n "$temporary_dir" ]; then rm -rf "$temporary_dir"; fi' EXIT HUP INT TERM

if [ "$force" = false ]; then
    if [ -f "$private_key" ] && [ -f "$public_key" ]; then
        temporary_dir=$(mktemp -d "$output_dir/.jwt-verify.XXXXXX")
        openssl pkey -in "$private_key" -pubout -out "$temporary_dir/public.pem"
        if ! cmp -s "$temporary_dir/public.pem" "$public_key"; then
            echo "Existing JWT public key does not match the private key; use --force to rotate both files." >&2
            exit 1
        fi
        echo "Reusing existing JWT key pair in $output_dir"
        exit 0
    fi
    if [ -e "$private_key" ] || [ -e "$public_key" ]; then
        echo "Incomplete JWT key pair in $output_dir; restore the missing file or use --force." >&2
        exit 1
    fi
else
    echo "Rotating JWT key pair; previously issued JWTs will no longer validate." >&2
fi

temporary_dir=$(mktemp -d "$output_dir/.jwt-generate.XXXXXX")
temporary_key="$temporary_dir/keypair.pem"
temporary_private="$temporary_dir/jwt-private.pem"
temporary_public="$temporary_dir/jwt-public.pem"

openssl genpkey -quiet -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -out "$temporary_key"
openssl pkey -in "$temporary_key" -pubout -out "$temporary_public"
openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt \
    -in "$temporary_key" -out "$temporary_private"

chmod 644 "$temporary_private" "$temporary_public"
mv -f "$temporary_private" "$private_key"
mv -f "$temporary_public" "$public_key"
echo "JWT key pair is ready in $output_dir"
