package io.github.minhyeok.coupon_prac.v1;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Map;

/** V4 UTC 데이터가 삭제·중복 변환 없이 V5 한국 시각으로 전환되는지 검증한다. */
@Testcontainers
class KoreanDateTimeMigrationTests {
    @Container static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Test
    void convertsEveryTimestampAndPreservesDailyDatesAndNullableQr() throws Exception {
        Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .target("4")
                .load()
                .migrate();
        try (Connection connection =
                        DriverManager.getConnection(
                                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    INSERT INTO campaign (id, store_id, owner_id, status, discount_target_type,
                      discount_type, discount_value, issue_quantity, usable_start_time, usable_end_time,
                      target_age_groups, daily_budget, start_date, end_date, created_at)
                    VALUES (1, 1, 1, 'ACTIVE', 'ALL', 'AMOUNT', 1000, 2, '11:00:00', '14:00:00',
                      '전체', 1000, '2026-09-23', '2026-09-25', '2026-09-23 14:59:59')
                    """);
            statement.executeUpdate(
                    """
                    INSERT INTO `user` (id, provider_user_id, status, created_at, updated_at)
                    VALUES (1, 'migration-1', 'ACTIVE', '2026-09-23 14:59:59', '2026-09-23 14:59:59'),
                           (2, 'migration-2', 'ACTIVE', '2026-09-23 14:59:59', '2026-09-23 14:59:59')
                    """);
            statement.executeUpdate(
                    """
                    INSERT INTO coupon_event (id, campaign_id, business_date, coupon_quantity,
                      issue_start_at, issue_end_at, usable_start_time, usable_end_time, status, created_at, updated_at)
                    VALUES (1, 1, '2026-09-23', 2, '2026-09-23 14:59:59', '2026-09-23 15:00:59',
                      '2026-09-23 14:59:59', '2026-09-23 15:00:59', 'ACTIVE',
                      '2026-09-23 14:59:59', '2026-09-23 14:59:59')
                    """);
            statement.executeUpdate(
                    """
                    INSERT INTO coupon_inventory (id, event_id, sequence_no, coupon_code, status,
                      usable_start_time, usable_end_time, created_at, updated_at)
                    SELECT seq.n, 1, seq.n, UNHEX(LPAD(seq.n, 32, '0')), 'ISSUED',
                      '2026-09-23 14:59:59', '2026-09-23 15:00:59', '2026-09-23 14:59:59', '2026-09-23 14:59:59'
                    FROM (SELECT 1 n UNION ALL SELECT 2) seq
                    """);
            statement.executeUpdate(
                    """
                    INSERT INTO user_coupon (id, event_id, user_id, coupon_code, status,
                      usable_start_time, usable_end_time, created_at, updated_at)
                    SELECT id, event_id, id, coupon_code, 'ISSUED', usable_start_time, usable_end_time,
                      created_at, updated_at FROM coupon_inventory
                    """);
            statement.executeUpdate(
                    """
                    UPDATE user_coupon SET qr_version = 1, qr_token = UNHEX(LPAD('3', 32, '0')),
                      qr_expires_at = '2026-09-23 15:00:59' WHERE id = 1
                    """);
            statement.executeUpdate("UPDATE user_coupon SET status = 'USED' WHERE id = 2");
            statement.executeUpdate(
                    """
                    INSERT INTO coupon_usage_history (id, user_coupon_id, discount_amount, created_at, updated_at)
                    VALUES (1, 2, 1000, '2026-09-23 14:59:59', '2026-09-23 14:59:59')
                    """);
            // The first two rows would temporarily share limit_date with the old +9 expression.
            statement.executeUpdate(
                    """
                    INSERT INTO coupon_daily_limit (id, user_id, issued_count, created_at, updated_at)
                    VALUES (1, 1, 3, '2026-09-23 14:59:59', '2026-09-23 14:59:59'),
                           (2, 1, 1, '2026-09-23 15:00:00', '2026-09-23 15:00:00'),
                           (3, 1, 1, '2026-09-24 18:00:00', '2026-09-24 18:00:00')
                    """);
        }
        Flyway flyway =
                Flyway.configure()
                        .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                        .load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        try (Connection connection =
                        DriverManager.getConnection(
                                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                Statement statement = connection.createStatement()) {
            LocalDateTime start = LocalDateTime.of(2026, 9, 23, 23, 59, 59);
            LocalDateTime end = LocalDateTime.of(2026, 9, 24, 0, 0, 59);
            for (Map.Entry<String, Integer> table :
                    Map.of(
                                    "campaign",
                                    1,
                                    "`user`",
                                    2,
                                    "coupon_event",
                                    1,
                                    "coupon_inventory",
                                    2,
                                    "coupon_daily_limit",
                                    3,
                                    "user_coupon",
                                    2,
                                    "coupon_usage_history",
                                    1)
                            .entrySet()) {
                try (ResultSet result =
                        statement.executeQuery("SELECT COUNT(*) FROM " + table.getKey())) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(table.getValue());
                }
                try (ResultSet result =
                        statement.executeQuery(
                                "SELECT * FROM " + table.getKey() + " WHERE id = 1")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getObject("created_at", LocalDateTime.class))
                            .isEqualTo(start);
                    if (!table.getKey().equals("campaign")) {
                        assertThat(result.getObject("updated_at", LocalDateTime.class))
                                .isEqualTo(start);
                    }
                    if (table.getKey().equals("coupon_event")
                            || table.getKey().equals("coupon_inventory")
                            || table.getKey().equals("user_coupon")) {
                        assertThat(result.getObject("usable_start_time", LocalDateTime.class))
                                .isEqualTo(start);
                        assertThat(result.getObject("usable_end_time", LocalDateTime.class))
                                .isEqualTo(end);
                    }
                }
            }
            try (ResultSet result =
                    statement.executeQuery(
                            """
                            SELECT c.start_date, c.end_date, c.usable_start_time, e.business_date,
                              e.issue_start_at, e.issue_end_at, u.qr_expires_at, u.qr_version, HEX(u.qr_token) token
                            FROM campaign c JOIN coupon_event e ON e.campaign_id = c.id
                            JOIN user_coupon u ON u.event_id = e.id WHERE u.id = 1
                            """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("start_date")).isEqualTo("2026-09-23");
                assertThat(result.getString("end_date")).isEqualTo("2026-09-25");
                assertThat(result.getString("business_date")).isEqualTo("2026-09-23");
                assertThat(result.getString("usable_start_time")).isEqualTo("11:00:00");
                assertThat(result.getObject("issue_start_at", LocalDateTime.class))
                        .isEqualTo(start);
                assertThat(result.getObject("issue_end_at", LocalDateTime.class)).isEqualTo(end);
                assertThat(result.getObject("qr_expires_at", LocalDateTime.class)).isEqualTo(end);
                assertThat(result.getInt("qr_version")).isEqualTo(1);
                assertThat(result.getString("token")).isEqualTo("00000000000000000000000000000003");
            }
            try (ResultSet result =
                    statement.executeQuery(
                            "SELECT qr_token, qr_expires_at FROM user_coupon WHERE id = 2")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getObject(1)).isNull();
                assertThat(result.getObject(2)).isNull();
            }
            try (ResultSet result =
                    statement.executeQuery(
                            "SELECT limit_date, issued_count FROM coupon_daily_limit ORDER BY"
                                    + " id")) {
                for (int day = 23; day <= 25; day++) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("2026-09-" + day);
                    assertThat(result.getInt(2)).isEqualTo(day == 23 ? 3 : 1);
                }
                assertThat(result.next()).isFalse();
            }
            assertThatThrownBy(
                            () ->
                                    statement.executeUpdate(
                                            """
                                            INSERT INTO coupon_daily_limit (user_id, issued_count, created_at, updated_at)
                                            VALUES (1, 0, '2026-09-24 23:00:00', '2026-09-24 23:00:00')
                                            """))
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }
}
