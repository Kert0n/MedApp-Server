#!/bin/sh
set -eu

umask 077
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_dir=$(CDPATH= cd -- "$script_dir/../../../.." && pwd)
output_dir=${1:-"$project_dir/.local/secrets"}
temporary_key="$output_dir/.jwt-keypair.pem"

mkdir -p "$output_dir"
chmod 700 "$output_dir"
trap 'rm -f "$temporary_key"' EXIT HUP INT TERM

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -out "$temporary_key"
openssl pkey -in "$temporary_key" -pubout -out "$output_dir/jwt-public.pem"
openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt \
    -in "$temporary_key" -out "$output_dir/jwt-private.pem"

# Standalone Docker Compose bind-mounts file-based secrets without changing
# their owner. The 0700 parent directory protects them on the host; 0644 lets
# the dedicated non-root application user read the mounted files in-container.
chmod 644 "$output_dir/jwt-private.pem" "$output_dir/jwt-public.pem"
