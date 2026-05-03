CREATE TABLE expense
(
    id          UUID                     NOT NULL PRIMARY KEY,
    trip_id     UUID                     NOT NULL,
    paid_by     UUID                     NOT NULL,
    amount      DECIMAL(19, 4)           NOT NULL CHECK (amount > 0),
    currency    VARCHAR(3)               NOT NULL,
    category    VARCHAR(50)              NOT NULL CHECK (category IN ('TRANSPORT', 'ACCOMMODATION', 'FOOD', 'ACTIVITY', 'OTHER')),
    description VARCHAR(255)             NOT NULL,
    receipt_key VARCHAR(500),
    split_mode  VARCHAR(50)              NOT NULL CHECK (split_mode IN ('EQUAL', 'CUSTOM', 'PERCENTAGE')),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted_at  TIMESTAMP WITH TIME ZONE
);

CREATE TABLE expense_split
(
    id           UUID           NOT NULL PRIMARY KEY,
    expense_id   UUID           NOT NULL REFERENCES expense (id) ON DELETE CASCADE,
    device_id    UUID           NOT NULL,
    share_amount DECIMAL(19, 4) NOT NULL CHECK (share_amount >= 0)
);

CREATE INDEX idx_expense_trip_id_created_at_desc
    ON expense (trip_id, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_expense_split_expense_id ON expense_split (expense_id);
