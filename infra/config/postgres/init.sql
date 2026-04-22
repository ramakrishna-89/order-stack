-- Orders table
CREATE TABLE IF NOT EXISTS orders (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id     VARCHAR(50)     NOT NULL,
    idempotency_key VARCHAR(100)    NOT NULL UNIQUE,
    status          VARCHAR(20)     NOT NULL DEFAULT 'CREATED',
    amount          DECIMAL(10,2)   NOT NULL,
    currency        VARCHAR(3)      NOT NULL DEFAULT 'INR',
    description     TEXT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- Outbox table (transactional outbox pattern)
CREATE TABLE IF NOT EXISTS outbox_events (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id VARCHAR(100) NOT NULL,
    event_type   VARCHAR(50)  NOT NULL,
    payload      JSONB        NOT NULL,
    published    BOOLEAN      NOT NULL DEFAULT FALSE,
    trace_id     VARCHAR(64),
    span_id      VARCHAR(32),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_outbox_unpublished ON outbox_events (published) WHERE published = FALSE;
CREATE INDEX IF NOT EXISTS idx_orders_customer    ON orders (customer_id);
CREATE INDEX IF NOT EXISTS idx_orders_status      ON orders (status);

