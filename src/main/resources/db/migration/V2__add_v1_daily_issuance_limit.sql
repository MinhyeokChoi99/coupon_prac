CREATE TABLE user_coupon_daily_limit (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id       BIGINT UNSIGNED NOT NULL,
    business_date DATE NOT NULL,
    issued_count  INT UNSIGNED NOT NULL DEFAULT 0,
    created_at    DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT uq_user_coupon_daily_limit_user_date UNIQUE (user_id, business_date),
    CONSTRAINT ck_user_coupon_daily_limit_count CHECK (issued_count BETWEEN 0 AND 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
