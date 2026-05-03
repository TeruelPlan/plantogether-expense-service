-- Story 5.2 — Multi-Currency Expense Entry
-- Adds the FX snapshot columns to expense rows. Pre-existing rows (single-currency
-- 5.1 entries) are backfilled with rate 1, reference_currency = currency, source LIVE.

ALTER TABLE expense
    ADD COLUMN exchange_rate DECIMAL(19, 6) NOT NULL DEFAULT 1.000000,
    ADD COLUMN amount_in_reference_currency DECIMAL(19, 4),
    ADD COLUMN reference_currency VARCHAR(3),
    ADD COLUMN rate_source VARCHAR(16) NOT NULL DEFAULT 'LIVE',
    ADD COLUMN rate_fetched_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW();

UPDATE expense
SET amount_in_reference_currency = amount
WHERE amount_in_reference_currency IS NULL;

UPDATE expense
SET reference_currency = currency
WHERE reference_currency IS NULL;

ALTER TABLE expense
    ALTER COLUMN amount_in_reference_currency SET NOT NULL,
    ALTER COLUMN reference_currency SET NOT NULL;

ALTER TABLE expense
    ADD CONSTRAINT chk_expense_rate_source CHECK (rate_source IN ('LIVE', 'CACHED', 'FALLBACK'));
