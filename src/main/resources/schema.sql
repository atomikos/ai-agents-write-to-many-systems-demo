-- The order database (step 1 of the refund)
CREATE TABLE IF NOT EXISTS orders (
    id          VARCHAR(64) PRIMARY KEY,
    customer_id VARCHAR(64)    NOT NULL,
    amount      NUMERIC(12, 2) NOT NULL,
    status      VARCHAR(16)    NOT NULL
);

-- Durable saga state: one row per refund attempt, keyed by the caller's idempotency key.
-- PENDING -> COMPLETED (inside the XA transaction) or PENDING -> COMPENSATING -> COMPENSATED.
CREATE TABLE IF NOT EXISTS refund_saga (
    refund_id   VARCHAR(64) PRIMARY KEY,
    order_id    VARCHAR(64) NOT NULL,
    customer_id VARCHAR(64) NOT NULL,
    status      VARCHAR(16) NOT NULL,
    detail      VARCHAR(512),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Stand-ins for downstream systems, so the demo can show what they saw.
CREATE TABLE IF NOT EXISTS billing_ledger (
    refund_id VARCHAR(64) PRIMARY KEY,
    order_id  VARCHAR(64)    NOT NULL,
    amount    NUMERIC(12, 2) NOT NULL
);

CREATE TABLE IF NOT EXISTS sent_email (
    refund_id   VARCHAR(64) PRIMARY KEY,
    customer_id VARCHAR(64) NOT NULL,
    sent_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- State of the fake CRM. A real CRM is a remote SaaS system; it only lives in this
-- database so that its state survives the crashes the demo provokes.
CREATE TABLE IF NOT EXISTS fake_crm_note (
    idempotency_key VARCHAR(64) PRIMARY KEY,
    customer_id     VARCHAR(64)  NOT NULL,
    note            VARCHAR(512) NOT NULL,
    status          VARCHAR(16)  NOT NULL
);

INSERT INTO orders (id, customer_id, amount, status) VALUES
    ('order-1001', 'customer-1', 49.90, 'PAID'),
    ('order-1002', 'customer-2', 120.00, 'PAID'),
    ('order-1003', 'customer-3', 15.50, 'PAID'),
    ('order-1004', 'customer-1', 250.00, 'PAID'),
    ('order-1005', 'customer-2', 9.99, 'PAID'),
    ('order-1006', 'customer-3', 74.25, 'PAID')
ON CONFLICT (id) DO NOTHING;
