#!/usr/bin/env bash
set -euo pipefail

certificate="${1:?controlled DoH certificate path is required}"
test -s "$certificate"

# Script instala CA confiável no perfil Chrome; só aceita o cert efêmero do DoH controlado.
if ! openssl x509 -in "$certificate" -noout -ext subjectAltName | grep -qE 'DNS:controlled-doh\.test(,|$)'; then
  printf '%s\n' "trust-controlled-doh: certificado sem SAN DNS:controlled-doh.test" >&2
  exit 1
fi
if openssl x509 -in "$certificate" -noout -checkend 172800 >/dev/null; then
  printf '%s\n' "trust-controlled-doh: certificado de longa duração recusado (esperado cert efêmero de 1 dia)" >&2
  exit 1
fi

# Chromium uses an existing legacy NSS Shared DB on Linux, including releases
# that otherwise default to $HOME/.local/share/pki/nssdb.
database="$HOME/.pki/nssdb"
install -d -m 0700 "$database"
if [[ ! -f "$database/cert9.db" ]]; then
  certutil -N --empty-password -d "sql:$database" >/dev/null
fi
certutil -D -d "sql:$database" -n javadriveless-controlled-doh >/dev/null 2>&1 || true
certutil -A -d "sql:$database" -t "P,," \
  -n javadriveless-controlled-doh -i "$certificate"
certutil -L -d "sql:$database" -n javadriveless-controlled-doh >/dev/null
