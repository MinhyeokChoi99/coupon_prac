-- Development reset, explicitly approved for disposable practice/load-test data.
-- V1/V2 are kept unchanged for Flyway checksum compatibility.
-- Do not deploy this migration against production data.
DROP TABLE user_coupon_daily_limit;
DROP TABLE user_coupon;
DROP TABLE coupon_inventory;
DROP TABLE coupon_event;

CREATE TABLE campaign (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    paused_reason VARCHAR(20) NULL,
    discount_target_type VARCHAR(20) NOT NULL,
    discount_type VARCHAR(20) NOT NULL,
    discount_value INT NOT NULL,
    target_menu_id BIGINT NULL,
    issue_quantity INT NOT NULL,
    usable_start_time TIME NOT NULL,
    usable_end_time TIME NOT NULL,
    min_order_amount INT NULL,
    notice VARCHAR(500) NULL,
    target_radius VARCHAR(10) NOT NULL DEFAULT '1km',
    target_gender VARCHAR(10) NOT NULL DEFAULT '전체',
    target_age_groups VARCHAR(50) NOT NULL,
    daily_budget INT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT ck_campaign_status CHECK (status IN ('DRAFT','SCHEDULED','ACTIVE','PAUSED','ENDED')),
    CONSTRAINT ck_campaign_quantity CHECK (issue_quantity > 0),
    CONSTRAINT ck_campaign_dates CHECK (start_date <= end_date),
    CONSTRAINT ck_campaign_discount CHECK (
        (discount_type = 'AMOUNT' AND discount_value >= 0) OR
        (discount_type = 'PERCENT' AND discount_value BETWEEN 0 AND 100)
    ),
    CONSTRAINT ck_campaign_target CHECK (
        (discount_target_type = 'ALL' AND target_menu_id IS NULL) OR
        (discount_target_type = 'TARGET_MENU' AND target_menu_id IS NOT NULL)
    ),
    CONSTRAINT ck_campaign_budget CHECK (daily_budget >= 0),
    CONSTRAINT ck_campaign_min_order CHECK (min_order_amount IS NULL OR min_order_amount >= 0),
    INDEX ix_campaign_store (store_id),
    INDEX ix_campaign_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `user` (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    provider_user_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uq_user_provider_id UNIQUE (provider_user_id),
    CONSTRAINT ck_user_status CHECK (status IN ('ACTIVE','SUSPENDED','WITHDRAWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE coupon_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    campaign_id BIGINT NOT NULL,
    business_date DATE NOT NULL,
    coupon_quantity INT NOT NULL,
    issue_start_at DATETIME NOT NULL,
    issue_end_at DATETIME NOT NULL,
    usable_start_time DATETIME NOT NULL,
    usable_end_time DATETIME NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT fk_event_campaign FOREIGN KEY (campaign_id) REFERENCES campaign(id),
    CONSTRAINT uq_event_campaign_date UNIQUE (campaign_id, business_date),
    CONSTRAINT ck_event_quantity CHECK (coupon_quantity > 0),
    CONSTRAINT ck_event_issue_period CHECK (issue_start_at < issue_end_at),
    CONSTRAINT ck_event_use_period CHECK (usable_start_time < usable_end_time),
    CONSTRAINT ck_event_status CHECK (status IN ('SCHEDULED','ACTIVE','PAUSED','SOLD_OUT','ENDED')),
    INDEX ix_event_start (status, issue_start_at),
    INDEX ix_event_end (status, issue_end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE coupon_inventory (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id BIGINT NOT NULL,
    sequence_no INT NOT NULL,
    coupon_code BINARY(16) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    usable_start_time DATETIME NOT NULL,
    usable_end_time DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT fk_inventory_event FOREIGN KEY (event_id) REFERENCES coupon_event(id),
    CONSTRAINT uq_inventory_sequence UNIQUE (event_id, sequence_no),
    CONSTRAINT uq_inventory_code UNIQUE (coupon_code),
    CONSTRAINT uq_inventory_event_code UNIQUE (event_id, coupon_code),
    CONSTRAINT ck_inventory_sequence CHECK (sequence_no > 0),
    CONSTRAINT ck_inventory_status CHECK (status IN ('AVAILABLE','ISSUED')),
    INDEX ix_inventory_event_status_sequence (event_id, status, sequence_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE coupon_daily_limit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    issued_count INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    -- DATETIME is stored in UTC; limits use the Korea calendar date.
    limit_date DATE GENERATED ALWAYS AS (DATE(created_at + INTERVAL 9 HOUR)) STORED,
    CONSTRAINT fk_daily_user FOREIGN KEY (user_id) REFERENCES `user`(id),
    CONSTRAINT uq_daily_user_date UNIQUE (user_id, limit_date),
    CONSTRAINT ck_daily_count CHECK (issued_count BETWEEN 0 AND 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_coupon (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    coupon_code BINARY(16) NOT NULL,
    qr_version INT NOT NULL DEFAULT 0,
    qr_token BINARY(16) NULL,
    qr_expires_at DATETIME NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ISSUED',
    usable_start_time DATETIME NOT NULL,
    usable_end_time DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT fk_user_coupon_event FOREIGN KEY (event_id) REFERENCES coupon_event(id),
    CONSTRAINT fk_user_coupon_user FOREIGN KEY (user_id) REFERENCES `user`(id),
    CONSTRAINT fk_user_coupon_inventory FOREIGN KEY (event_id, coupon_code)
        REFERENCES coupon_inventory(event_id, coupon_code),
    CONSTRAINT uq_user_coupon_event_user UNIQUE (event_id, user_id),
    CONSTRAINT uq_user_coupon_code UNIQUE (coupon_code),
    CONSTRAINT uq_user_coupon_qr_token UNIQUE (qr_token),
    CONSTRAINT ck_user_coupon_status CHECK (status IN ('ISSUED','USED','EXPIRED','REMOVED')),
    CONSTRAINT ck_user_coupon_qr_version CHECK (qr_version >= 0),
    CONSTRAINT ck_user_coupon_qr_pair CHECK (
        (qr_token IS NULL AND qr_expires_at IS NULL) OR
        (qr_token IS NOT NULL AND qr_expires_at IS NOT NULL)
    ),
    INDEX ix_user_coupon_lookup (user_id, created_at, id),
    INDEX ix_user_coupon_event_created (event_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE coupon_usage_history (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_coupon_id BIGINT NOT NULL,
    discount_amount INT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT fk_usage_user_coupon FOREIGN KEY (user_coupon_id) REFERENCES user_coupon(id),
    CONSTRAINT uq_usage_user_coupon UNIQUE (user_coupon_id),
    CONSTRAINT ck_usage_discount CHECK (discount_amount >= 0),
    INDEX ix_usage_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
