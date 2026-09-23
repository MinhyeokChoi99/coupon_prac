package io.github.minhyeok.coupon_prac.v1.loadtest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import io.github.minhyeok.coupon_prac.v1.campaign.entity.Campaign;
import io.github.minhyeok.coupon_prac.v1.campaign.repository.CampaignRepository;
import io.github.minhyeok.coupon_prac.v1.couponevent.entity.*;
import io.github.minhyeok.coupon_prac.v1.couponevent.repository.CouponEventRepository;
import io.github.minhyeok.coupon_prac.v1.couponevent.service.CouponEventLifecycleService;
import io.github.minhyeok.coupon_prac.v1.couponinventory.repository.CouponInventoryRepository;
import io.github.minhyeok.coupon_prac.v1.couponinventory.service.CouponInventoryLoader;
import io.github.minhyeok.coupon_prac.v1.usercoupon.repository.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static io.github.minhyeok.coupon_prac.v1.common.time.CouponTime.*;

@Component @Profile("loadtest")
public class LoadTestFixtureInitializer implements ApplicationRunner {
    public static final String FIXTURE_MARKER = "coupon-prac-load-test-v3";
    public static final long USER_ID_START = 10_000_000L;
    public static final long USER_ID_END = 10_049_999L;
    private static final Logger log = LoggerFactory.getLogger(LoadTestFixtureInitializer.class);
    private final CampaignRepository campaigns;
    private final CouponEventRepository events;
    private final CouponEventLifecycleService lifecycle;
    private final CouponInventoryRepository inventory;
    private final CouponInventoryLoader loader;
    private final UserCouponDailyLimitRepository limits;
    private final UserCouponRepository coupons;
    private final JdbcTemplate jdbc;
    private final boolean reset;
    private final Duration duration;
    private final TransactionTemplate transaction;
    public LoadTestFixtureInitializer(CampaignRepository campaigns, CouponEventRepository events,
            CouponEventLifecycleService lifecycle, CouponInventoryRepository inventory,
            CouponInventoryLoader loader, UserCouponDailyLimitRepository limits, UserCouponRepository coupons,
            JdbcTemplate jdbc, PlatformTransactionManager manager,
            @Value("${coupon.loadtest.reset:false}") boolean reset,
            @Value("${coupon.loadtest.event-duration:PT10M}") Duration duration) {
        this.campaigns = campaigns; this.events = events; this.lifecycle = lifecycle;
        this.inventory = inventory; this.loader = loader; this.limits = limits; this.coupons = coupons;
        this.jdbc = jdbc; this.reset = reset; this.duration = duration;
        this.transaction = new TransactionTemplate(manager);
    }
    @Override public void run(ApplicationArguments args) {
        if (duration.isNegative() || duration.isZero()) throw new IllegalArgumentException("Positive event duration required");
        verifyReservedUsers();
        if (reset) resetFixtures();
        createUsers();
        var fixtures = events.findFixtures(FIXTURE_MARKER);
        if (fixtures.isEmpty()) fixtures = createFixtures();
        if (fixtures.size() != 30) throw new IllegalStateException("Expected exactly 30 fixture events; reset fixtures");
        Instant now = Instant.now();
        for (var event : fixtures) {
            if (!now.isBefore(event.getIssueEndAt())) throw new IllegalStateException("Expired load-test fixture; use reset=true");
            loader.load(event.getId(), now);
            if (event.getStatus() == CouponEventStatus.SCHEDULED) lifecycle.activate(event.getId(), now);
        }
        log.info("Load-test fixture ready: 30 campaigns/events, 15000 inventory rows, 50000 users");
    }
    private void verifyReservedUsers() {
        Long conflicts = jdbc.queryForObject("""
            SELECT COUNT(*) FROM `user` WHERE id BETWEEN ? AND ?
            AND provider_user_id <> CONCAT('load-test-user-', id)
            """, Long.class, USER_ID_START, USER_ID_END);
        if (conflicts != null && conflicts > 0) throw new IllegalStateException("Reserved load-test IDs contain non-fixture users");
    }
    private void createUsers() {
        transaction.executeWithoutResult(tx -> {
            Instant now = seconds(Instant.now());
            var rows = new ArrayList<Long>();
            for (long id = USER_ID_START; id <= USER_ID_END; id++) rows.add(id);
            jdbc.batchUpdate("""
                INSERT INTO `user` (id, provider_user_id, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', ?, ?) ON DUPLICATE KEY UPDATE id = id
                """, rows, 1000, (ps, id) -> {
                ps.setLong(1, id); ps.setString(2, "load-test-user-" + id);
                ps.setTimestamp(3, Timestamp.from(now)); ps.setTimestamp(4, Timestamp.from(now));
            });
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE id BETWEEN ? AND ? AND status = 'ACTIVE'",
                    Long.class, USER_ID_START, USER_ID_END);
            if (count == null || count != 50_000) throw new IllegalStateException("Fixture users missing or inactive");
        });
    }
    protected void resetFixtures() {
        transaction.executeWithoutResult(tx -> {
            // Delete only events/campaigns explicitly tagged as this load-test fixture.
            limits.deleteAllByUserIdRange(USER_ID_START, USER_ID_END);
            for (var event : events.findFixtures(FIXTURE_MARKER)) {
                jdbc.update("""
                    DELETE h FROM coupon_usage_history h JOIN user_coupon u ON u.id = h.user_coupon_id
                    WHERE u.event_id = ?
                    """, event.getId());
                coupons.deleteAllByEventId(event.getId());
                inventory.deleteByEventId(event.getId());
                events.delete(event);
                events.flush();
            }
            campaigns.deleteAll(campaigns.findByNoticeOrderByIdAsc(FIXTURE_MARKER));
            campaigns.flush();
        });
    }
    protected List<CouponEvent> createFixtures() {
        return transaction.execute(tx -> {
            Instant now = seconds(Instant.now());
            Instant start = now.minusSeconds(1), end = now.plus(duration), useEnd = end.plusSeconds(3600);
            var result = new ArrayList<CouponEvent>();
            for (int store = 1; store <= 30; store++) {
                var campaign = campaigns.saveAndFlush(Campaign.scheduled((long) store, (long) store, 500,
                        start.atZone(BUSINESS_ZONE).toLocalTime(), useEnd.atZone(BUSINESS_ZONE).toLocalTime(),
                        businessDate(start), businessDate(useEnd), "AMOUNT", 1000, null, null,
                        100_000, FIXTURE_MARKER, now));
                result.add(events.saveAndFlush(CouponEvent.scheduled(campaign.getId(), businessDate(now),
                        500, start, end, start, useEnd, now)));
            }
            return result;
        });
    }
}
