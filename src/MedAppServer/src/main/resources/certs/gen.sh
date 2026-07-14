#!/bin/sh
set -eu

umask 077
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_dir=$(CDPATH= cd -- "$script_dir/../../../.." && pwd)
output_dir=${1:-"$project_dir/.local/secrets"}
temporary_key="$output_dir/.jwt-keypair.pem"

mkdir -p "$output_dir"
trap 'rm -f "$temporary_key"' EXIT HUP INT TERM

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -out "$temporary_key"
openssl pkey -in "$temporary_key" -pubout -out "$output_dir/jwt-public.pem"
openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt \
    -in "$temporary_key" -out "$output_dir/jwt-private.pem"

chmod 600 "$output_dir/jwt-private.pem" "$output_dir/jwt-public.pem"
