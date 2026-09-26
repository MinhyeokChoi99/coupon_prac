-- V1-V4 DATETIME values are UTC. Convert existing values once to Korea local time.
-- Stop all API writers and k6 runs before deploying this migration; do not mix old/new apps.
-- MySQL DDL is not transactional. Back up first; restore after a failed partial migration
-- rather than rerunning these +9-hour updates against already converted rows.

-- Retain an index for the user FK while temporarily removing the generated-date unique key.
-- Intermediate dates under the OLD expression can collide even when final dates are distinct.
ALTER TABLE coupon_daily_limit ADD INDEX ix_daily_user_timezone_migration (user_id);
ALTER TABLE coupon_daily_limit DROP INDEX uq_daily_user_date;

START TRANSACTION;

UPDATE campaign SET created_at = created_at + INTERVAL 9 HOUR;

UPDATE `user`
SET created_at = created_at + INTERVAL 9 HOUR,
    updated_at = updated_at + INTERVAL 9 HOUR;

UPDATE coupon_event
SET issue_start_at = issue_start_at + INTERVAL 9 HOUR,
    issue_end_at = issue_end_at + INTERVAL 9 HOUR,
    usable_start_time = usable_start_time + INTERVAL 9 HOUR,
    usable_end_time = usable_end_time + INTERVAL 9 HOUR,
    created_at = created_at + INTERVAL 9 HOUR,
    updated_at = updated_at + INTERVAL 9 HOUR;

UPDATE coupon_inventory
SET usable_start_time = usable_start_time + INTERVAL 9 HOUR,
    usable_end_time = usable_end_time + INTERVAL 9 HOUR,
    created_at = created_at + INTERVAL 9 HOUR,
    updated_at = updated_at + INTERVAL 9 HOUR;

UPDATE coupon_daily_limit
SET created_at = created_at + INTERVAL 9 HOUR,
    updated_at = updated_at + INTERVAL 9 HOUR;

UPDATE user_coupon
SET qr_expires_at = qr_expires_at + INTERVAL 9 HOUR,
    usable_start_time = usable_start_time + INTERVAL 9 HOUR,
    usable_end_time = usable_end_time + INTERVAL 9 HOUR,
    created_at = created_at + INTERVAL 9 HOUR,
    updated_at = updated_at + INTERVAL 9 HOUR;

UPDATE coupon_usage_history
SET created_at = created_at + INTERVAL 9 HOUR,
    updated_at = updated_at + INTERVAL 9 HOUR;

COMMIT;

-- TIME and DATE columns were already Korean local values and must NOT be shifted.
ALTER TABLE coupon_daily_limit
    MODIFY COLUMN limit_date DATE GENERATED ALWAYS AS (DATE(created_at)) STORED,
    ADD CONSTRAINT uq_daily_user_date UNIQUE (user_id, limit_date);
ALTER TABLE coupon_daily_limit DROP INDEX ix_daily_user_timezone_migration;
