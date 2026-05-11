-- Phase 1: introduce trip_member_id alongside device_id for cross-service identity migration.
-- See docs/PLAN_device_id_to_member_id.md.

ALTER TABLE expense
    ADD COLUMN paid_by_trip_member_id UUID;

CREATE INDEX idx_expense_paid_by_member ON expense (paid_by_trip_member_id);

ALTER TABLE expense_split
    ADD COLUMN trip_member_id UUID;

CREATE INDEX idx_expense_split_member ON expense_split (trip_member_id);
