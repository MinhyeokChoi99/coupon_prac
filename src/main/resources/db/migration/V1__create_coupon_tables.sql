CREATE TABLE coupon_event (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name              VARCHAR(100) NOT NULL,
    total_quantity    INT UNSIGNED NOT NULL,
    issue_start_at    DATETIME(6) NOT NULL,
    issue_end_at      DATETIME(6) NOT NULL,
    coupon_expires_at DATETIME(6) NOT NULL,
    status            VARCHAR(20) NOT NULL,
    created_at        DATETIME(6) NOT NULL,
    updated_at        DATETIME(6) NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT ck_coupon_event_quantity CHECK (total_quantity > 0),
    CONSTRAINT ck_coupon_event_issue_period CHECK (issue_start_at < issue_end_at),
    CONSTRAINT ck_coupon_event_status CHECK (
        status IN ('DRAFT', 'PREPARING', 'ACTIVE', 'CLOSING', 'ENDED', 'CANCELLED')
    ),
    INDEX idx_coupon_event_start (status, issue_start_at),
    INDEX idx_coupon_event_end (status, issue_end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE coupon_inventory (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    event_id    BIGINT UNSIGNED NOT NULL,
    sequence_no INT UNSIGNED NOT NULL,
    coupon_code VARCHAR(64) NOT NULL,
    status      VARCHAR(20) NOT NULL,
    issued_at   DATETIME(6) NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT fk_coupon_inventory_event
        FOREIGN KEY (event_id) REFERENCES coupon_event(id),
    CONSTRAINT uq_coupon_inventory_sequence UNIQUE (event_id, sequence_no),
    CONSTRAINT uq_coupon_inventory_code UNIQUE (coupon_code),
    CONSTRAINT ck_coupon_inventory_status CHECK (
        status IN ('AVAILABLE', 'ISSUED', 'INVALIDATED')
    ),
    INDEX idx_coupon_inventory_warmup (event_id, status, sequence_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_coupon (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    event_id    BIGINT UNSIGNED NOT NULL,
    user_id     BIGINT UNSIGNED NOT NULL,
    coupon_code VARCHAR(64) NOT NULL,
    status      VARCHAR(20) NOT NULL,
    issued_at   DATETIME(6) NOT NULL,
    used_at     DATETIME(6) NULL,
    expires_at  DATETIME(6) NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT fk_user_coupon_event
        FOREIGN KEY (event_id) REFERENCES coupon_event(id),
    CONSTRAINT uq_user_coupon_per_event UNIQUE (event_id, user_id),
    CONSTRAINT uq_user_coupon_code UNIQUE (coupon_code),
    CONSTRAINT ck_user_coupon_status CHECK (
        status IN ('ISSUED', 'USED', 'EXPIRED', 'CANCELLED')
    ),
    INDEX idx_user_coupon_lookup (user_id, status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
