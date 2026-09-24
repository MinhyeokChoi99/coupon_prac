-- Keep already-applied V1/V2/V3 checksums unchanged.
-- Existing prepared rows become ACTIVE; issuance still checks issue_start_at/issue_end_at.
UPDATE campaign SET status = 'ACTIVE' WHERE status = 'SCHEDULED';
UPDATE coupon_event SET status = 'ACTIVE' WHERE status = 'SCHEDULED';

ALTER TABLE campaign
    DROP CHECK ck_campaign_status,
    ADD CONSTRAINT ck_campaign_status CHECK (status IN ('DRAFT', 'ACTIVE', 'PAUSED', 'ENDED'));

ALTER TABLE coupon_event
    ALTER COLUMN status SET DEFAULT 'ACTIVE',
    DROP CHECK ck_event_status,
    ADD CONSTRAINT ck_event_status CHECK (status IN ('ACTIVE', 'PAUSED', 'SOLD_OUT', 'ENDED'));
