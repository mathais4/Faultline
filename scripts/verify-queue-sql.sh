#!/usr/bin/env bash
# Runs the exact SQL statements used by PostgresJobQueue against a real PostgreSQL via psql and checks the queue
# semantics: dedup, claim, visibility timeout redelivery, fencing tokens, dead-lettering and SKIP LOCKED.
# It drops and recreates the `jobs` table in the target database.
#
#   PGHOST=localhost PGPORT=5432 PGUSER=faultline PGDATABASE=faultline scripts/verify-queue-sql.sh
set -u
HERE="$(cd "$(dirname "$0")/.." && pwd)"
PSQL="${PSQL:-psql}"
P0="$PSQL -h ${PGHOST:-127.0.0.1} -p ${PGPORT:-5432} -U ${PGUSER:-faultline} ${PGDATABASE:-faultline} -v ON_ERROR_STOP=1 -tA"; P() { $P0 "$@" | grep -v -E "^(INSERT [0-9]+ [0-9]+|UPDATE [0-9]+|TRUNCATE.*|DROP.*|CREATE.*|BEGIN|COMMIT)$"; }
SCHEMA="$HERE/src/main/resources/io/faultline/schema.sql"
fail=0
check() { # name expected actual
  if [ "$2" == "$3" ]; then echo "PASS $1"; else echo "FAIL $1: expected [$2] got [$3]"; fail=1; fi
}
$P0 -c "DROP TABLE IF EXISTS jobs" >/dev/null 2>&1
if $P0 -f "$SCHEMA" >/dev/null 2>&1; then echo "PASS schema applies"; else echo "FAIL schema applies"; fail=1; fi
if $P0 -f "$SCHEMA" >/dev/null 2>&1; then echo "PASS schema idempotent (IF NOT EXISTS)"; else echo "FAIL schema idempotent"; fail=1; fi

ENQ="INSERT INTO jobs (order_key, dedup_key, payload, max_attempts) VALUES ('o1','d1','p',3) ON CONFLICT (dedup_key) DO NOTHING RETURNING id"
a=$(P -c "$ENQ" | head -1); b=$(P -c "$ENQ" | head -1)
check "enqueue returns id" "1" "$a"
check "duplicate dedup_key suppressed" "" "$b"

CLAIM="UPDATE jobs SET state='running', attempts=attempts+1, claim_token=gen_random_uuid(), claimed_by='w1', visible_at=clock_timestamp()+(300*interval '1 millisecond') WHERE id IN (SELECT id FROM jobs WHERE state IN ('queued','running') AND visible_at<=clock_timestamp() AND attempts<max_attempts ORDER BY visible_at,id FOR UPDATE SKIP LOCKED LIMIT 5) RETURNING id, claim_token::text"
r1=$(P -c "$CLAIM" | head -1); tok1=${r1#*|}
check "first claim gets job 1" "1" "${r1%%|*}"
r2=$(P -c "$CLAIM" | head -1)
check "invisible while running" "" "$r2"
sleep 0.4
r3=$(P -c "$CLAIM" | head -1); tok2=${r3#*|}
check "redelivered after visibility timeout" "1" "${r3%%|*}"
[ "$tok1" != "$tok2" ] && echo "PASS claim token regenerated" || { echo "FAIL token reused"; fail=1; }

ACK="UPDATE jobs SET state='done', completed_at=clock_timestamp() WHERE id=1 AND claim_token='%s'::uuid AND state='running'"
n=$(P -c "$(printf "$ACK" "$tok1") RETURNING 1" | head -1)
check "stale-token ack rejected (fencing)" "" "$n"
n=$(P -c "$(printf "$ACK" "$tok2") RETURNING 1" | head -1)
check "current-token ack accepted" "1" "$n"
st=$(P -c "SELECT state, attempts FROM jobs WHERE id=1")
check "job done after 2 attempts" "done|2" "$st"

# dead-lettering: max_attempts=1, claimed once, visibility lapses, never acked
P -c "INSERT INTO jobs (order_key,payload,max_attempts) VALUES ('o2','p',1)" >/dev/null
P -c "$CLAIM" >/dev/null; sleep 0.4
P -c "UPDATE jobs SET state='dead', last_error=COALESCE(last_error,'visibility timeout on final attempt') WHERE state IN ('queued','running') AND visible_at<=clock_timestamp() AND attempts>=max_attempts" >/dev/null
check "exhausted job dead-lettered" "dead" "$(P -c "SELECT state FROM jobs WHERE order_key='o2'")"

# nack -> queued with delay, then dead on final attempt
P -c "INSERT INTO jobs (order_key,payload,max_attempts) VALUES ('o3','p',2)" >/dev/null
r=$(P -c "$CLAIM" | head -1); id=${r%%|*}; tk=${r#*|}
NACK="UPDATE jobs SET state=CASE WHEN attempts>=max_attempts THEN 'dead' ELSE 'queued' END, visible_at=clock_timestamp()+(100*interval '1 millisecond'), last_error='boom' WHERE id=$id AND claim_token='%s'::uuid AND state='running' RETURNING state"
check "nack requeues with attempts left" "queued" "$(P -c "$(printf "$NACK" "$tk")" | head -1)"
sleep 0.2
r=$(P -c "$CLAIM" | head -1); tk=${r#*|}
check "nack on final attempt dead-letters" "dead" "$(P -c "$(printf "$NACK" "$tk")" | head -1)"

# SKIP LOCKED: session A holds the row lock for 1.5s; session B must get a *different* row, not block.
P -c "TRUNCATE jobs RESTART IDENTITY" >/dev/null
P -c "INSERT INTO jobs (order_key,payload,max_attempts) SELECT 'k'||g,'p',3 FROM generate_series(1,2) g" >/dev/null
( P -c "BEGIN; SELECT id FROM jobs WHERE state='queued' ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1; SELECT pg_sleep(1.5); COMMIT" >/dev/null ) &
sleep 0.4
t0=$(date +%s.%N)
got=$(P -c "SELECT id FROM jobs WHERE state='queued' ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1" | head -1)
t1=$(date +%s.%N)
wait
el=$(echo "$t1 - $t0" | bc)
check "SKIP LOCKED returns other row" "2" "$got"
if (( $(echo "$el < 1.0" | bc) )); then echo "PASS did not block (${el}s)"; else echo "FAIL blocked ${el}s"; fail=1; fi

# stats query
P -c "SELECT CASE WHEN state IN ('queued','running') AND attempts>=max_attempts AND visible_at<=clock_timestamp() THEN 'dead' ELSE state END AS s, count(*) FROM jobs GROUP BY 1" >/dev/null && echo "PASS stats query valid"
P -c "SELECT order_key, sum(attempts)::int, count(*)::int FROM jobs GROUP BY order_key" >/dev/null && echo "PASS delivery-count query valid"
# Concurrent first-start: gateway, worker and lab all create the schema at once under docker compose. The queue holds
# an advisory lock around the DDL; without it, concurrent CREATE TABLE IF NOT EXISTS fails on a catalog race.
races=0
for round in 1 2 3; do
  $P0 -c "DROP TABLE IF EXISTS jobs" >/dev/null 2>&1
  for i in $(seq 1 8); do
    ( $P0 -c "SELECT pg_advisory_lock(7272701)" -f "$SCHEMA" -c "SELECT pg_advisory_unlock(7272701)" >/dev/null 2>&1 || echo race ) &
  done > /tmp/faultline-race.$$ 2>&1
  wait
  races=$((races + $(grep -c race /tmp/faultline-race.$$ || true)))
done
rm -f /tmp/faultline-race.$$
check "24 concurrent schema creations under the advisory lock all succeed" "0" "$races"
exit $fail
