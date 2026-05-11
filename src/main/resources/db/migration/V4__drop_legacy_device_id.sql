-- Phase 3: drop legacy device_id columns now that all callers consume trip_member_id.
-- See docs/PROGRESS_device_id_to_member_id.md.

ALTER TABLE expense
    DROP COLUMN paid_by;

ALTER TABLE expense
    ALTER COLUMN paid_by_trip_member_id SET NOT NULL;

ALTER TABLE expense_split
    DROP COLUMN device_id;

ALTER TABLE expense_split
    ALTER COLUMN trip_member_id SET NOT NULL;
