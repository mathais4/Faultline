-- Faultline job queue schema (PostgreSQL 13+; gen_random_uuid() is built in from 13).
--
-- A job is 'queued' or 'running'; both are claimable once visible_at has passed. 'running' + an expired
-- visible_at is exactly an SQS message whose visibility timeout lapsed: the worker may have crashed, been slow,
-- or lost its ack. That path is the source of duplicate deliveries.

CREATE TABLE IF NOT EXISTS jobs (
    id            BIGSERIAL PRIMARY KEY,
    order_key     TEXT        NOT NULL,
    dedup_key     TEXT        UNIQUE,                       -- optional producer-side idempotency key
    payload       TEXT        NOT NULL,
    state         TEXT        NOT NULL DEFAULT 'queued'
                  CHECK (state IN ('queued', 'running', 'done', 'dead')),
    attempts      INT         NOT NULL DEFAULT 0,
    max_attempts  INT         NOT NULL,
    visible_at    TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    claim_token   UUID,                                     -- fencing token, regenerated on every claim
    claimed_by    TEXT,
    last_error    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at  TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS jobs_ready_idx ON jobs (visible_at) WHERE state IN ('queued', 'running');
CREATE INDEX IF NOT EXISTS jobs_order_idx ON jobs (order_key);
