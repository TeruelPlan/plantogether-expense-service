-- Story 5.4.2: persisted settlement transfers (mark-as-done).
-- A row exists only once a transfer has actually been marked done. Identity is the per-trip
-- member id (the service migrated device-id -> member-id in V3/V4). No FK on trip_id — trip
-- existence is verified via gRPC, per project convention.

CREATE TABLE settlement_transfer (
    id UUID PRIMARY KEY,
    trip_id UUID NOT NULL,
    from_member_id UUID NOT NULL,
    to_member_id UUID NOT NULL,
    amount DECIMAL(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    settled_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    settled_by_member_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_settlement_amount_positive CHECK (amount > 0)
);

-- Collapses double-tap duplicates and backs the idempotent re-tap path (AC 4).
ALTER TABLE settlement_transfer
    ADD CONSTRAINT uk_settlement_transfer_pair
    UNIQUE (trip_id, from_member_id, to_member_id, amount, currency);

CREATE INDEX idx_settlement_transfer_trip_id ON settlement_transfer (trip_id);
