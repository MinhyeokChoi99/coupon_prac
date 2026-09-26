package io.github.minhyeok.coupon_prac.v1;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** 기존 V3 데이터가 새 상태 모델로 안전하게 전환되는지 독립 DB에서 검증한다. */
@Testcontainers
class SchemaMigrationTests {
    @Container static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Test
    void migratesPreviouslyPreparedRowsWithoutDeletingThem() throws Exception {
        Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration")
                .target("3")
                .load()
                .migrate();
        try (Connection connection =
                        DriverManager.getConnection(
                                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                Statement statement = connection.createStatement()) {
            // The legacy value deliberately exists only in this upgrade test and migration history.
            statement.executeUpdate(
                    """
                    INSERT INTO campaign (id, store_id, owner_id, status, discount_target_type, discount_type,
                      discount_value, issue_quantity, usable_start_time, usable_end_time,
                      target_age_groups, daily_budget, start_date, end_date, created_at)
                    VALUES (1, 1, 1, 'SCHEDULED', 'ALL', 'AMOUNT', 1000, 1, '11:00:00', '14:00:00',
                      '전체', 1000, '2026-09-24', '2026-09-24', '2026-09-24 00:00:00')
                    """);
            statement.executeUpdate(
                    """
                    INSERT INTO coupon_event (id, campaign_id, business_date, coupon_quantity,
                      issue_start_at, issue_end_at, usable_start_time, usable_end_time,
                      created_at, updated_at)
                    VALUES (1, 1, '2026-09-24', 1, '2026-09-24 02:00:00', '2026-09-24 04:00:00',
                      '2026-09-24 02:00:00', '2026-09-24 05:00:00',
                      '2026-09-24 00:00:00', '2026-09-24 00:00:00')
                    """);
        }
        Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try (Connection connection =
                        DriverManager.getConnection(
                                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                Statement statement = connection.createStatement()) {
            try (ResultSet result =
                    statement.executeQuery(
                            """
                            SELECT c.status AS campaign_status, e.status AS event_status,
                              e.coupon_quantity, e.issue_start_at
                            FROM campaign c JOIN coupon_event e ON e.campaign_id = c.id WHERE c.id = 1
                            """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("campaign_status")).isEqualTo("ACTIVE");
                assertThat(result.getString("event_status")).isEqualTo("ACTIVE");
                assertThat(result.getInt("coupon_quantity")).isEqualTo(1);
                assertThat(result.getObject("issue_start_at", java.time.LocalDateTime.class))
                        .isEqualTo(java.time.LocalDateTime.of(2026, 9, 24, 11, 0));
                assertThat(result.next()).isFalse();
            }
            try (ResultSet result =
                    statement.executeQuery(
                            """
                            SELECT column_default FROM information_schema.columns
                            WHERE table_schema = DATABASE() AND table_name = 'coupon_event' AND column_name = 'status'
                            """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("ACTIVE");
            }
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () ->
                                    statement.executeUpdate(
                                            "UPDATE coupon_event SET status = 'SCHEDULED' WHERE id"
                                                    + " = 1"))
                    .isInstanceOf(java.sql.SQLException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () ->
                                    statement.executeUpdate(
                                            "UPDATE campaign SET status = 'SCHEDULED' WHERE id ="
                                                    + " 1"))
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }
}
