#!/usr/bin/env bash
# Start a throwaway PostgreSQL without Docker (needs the PostgreSQL server binaries installed, and must not run as root).
# stdout is ONLY the export line, so you can do:   eval "$(scripts/local-postgres.sh)"
# Stop with: scripts/local-postgres.sh stop
#   FAULTLINE_PGDIR   data directory      (default /tmp/faultline-pg)
#   FAULTLINE_PGPORT  port                (default 5432)
set -euo pipefail
PGBIN="$(ls -d /usr/lib/postgresql/*/bin | sort -V | tail -1)"
DIR="${FAULTLINE_PGDIR:-/tmp/faultline-pg}"
PORT="${FAULTLINE_PGPORT:-5432}"
if [ "${1:-}" = "stop" ]; then "$PGBIN/pg_ctl" -D "$DIR" stop >&2; exit 0; fi
if [ ! -d "$DIR" ]; then
  "$PGBIN/initdb" -D "$DIR" -U faultline --auth=trust >&2
fi
if ! "$PGBIN/pg_ctl" -D "$DIR" status >/dev/null 2>&1; then
  "$PGBIN/pg_ctl" -D "$DIR" -o "-p $PORT -k $DIR -c listen_addresses=127.0.0.1" -l "$DIR/log" -w start >&2
fi
"$PGBIN/createdb" -h 127.0.0.1 -p "$PORT" -U faultline faultline 2>/dev/null || true
echo "export FAULTLINE_DB_URL=jdbc:postgresql://127.0.0.1:$PORT/faultline FAULTLINE_DB_USER=faultline FAULTLINE_DB_PASSWORD=x"
