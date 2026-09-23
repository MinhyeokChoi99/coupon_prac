package io.github.minhyeok.coupon_prac.v1;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import io.github.minhyeok.coupon_prac.v1.campaign.entity.Campaign;
import io.github.minhyeok.coupon_prac.v1.campaign.repository.CampaignRepository;
import io.github.minhyeok.coupon_prac.v1.common.exception.*;
import io.github.minhyeok.coupon_prac.v1.couponevent.entity.*;
import io.github.minhyeok.coupon_prac.v1.couponevent.repository.CouponEventRepository;
import io.github.minhyeok.coupon_prac.v1.couponevent.service.*;
import io.github.minhyeok.coupon_prac.v1.couponinventory.entity.CouponInventoryStatus;
import io.github.minhyeok.coupon_prac.v1.couponinventory.repository.CouponInventoryRepository;
import io.github.minhyeok.coupon_prac.v1.couponinventory.service.CouponInventoryLoader;
import io.github.minhyeok.coupon_prac.v1.couponusage.entity.CouponUsageHistory;
import io.github.minhyeok.coupon_prac.v1.couponusage.repository.CouponUsageHistoryRepository;
import io.github.minhyeok.coupon_prac.v1.user.entity.CouponUser;
import io.github.minhyeok.coupon_prac.v1.user.repository.CouponUserRepository;
import io.github.minhyeok.coupon_prac.v1.usercoupon.dto.*;
import io.github.minhyeok.coupon_prac.v1.usercoupon.entity.UserCouponStatus;
import io.github.minhyeok.coupon_prac.v1.usercoupon.repository.*;
import io.github.minhyeok.coupon_prac.v1.usercoupon.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import static io.github.minhyeok.coupon_prac.v1.common.time.CouponTime.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class CouponV1ApplicationTests {
    @Container @ServiceConnection
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");
    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired CampaignRepository campaigns;
    @Autowired CouponUserRepository users;
    @Autowired CouponEventRepository events;
    @Autowired CouponEventLifecycleService lifecycle;
    @Autowired CouponEventPreparationService preparation;
    @Autowired CouponInventoryLoader loader;
    @Autowired CouponInventoryRepository inventory;
    @Autowired UserCouponIssuanceService issuance;
    @Autowired UserCouponAccessService access;
    @Autowired UserCouponRepository coupons;
    @Autowired UserCouponDailyLimitRepository limits;
    @Autowired CouponUsageHistoryRepository history;
    @Autowired PlatformTransactionManager manager;
    @Autowired MutableClock clock;

    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>();
        void set(Instant value) { now.set(value); }
        @Override public Instant instant() { return now.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
    }
    @BeforeEach void resetDisposableTestDatabase() {
        for (String table : List.of("coupon_usage_history", "user_coupon", "coupon_daily_limit",
                "coupon_inventory", "coupon_event", "campaign", "`user`")) jdbc.update("DELETE FROM " + table);
        clock.set(Instant.parse("2026-09-23T03:00:00Z"));
    }
    @Test void validatesCurrentSchemaAndUtcSession() {
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class)).isEqualTo("+00:00");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
                AND table_name IN ('campaign','coupon_event','coupon_inventory','user','coupon_daily_limit','user_coupon','coupon_usage_history')
                """, Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
                AND table_name = 'coupon_event' AND column_name IN ('name','total_quantity','coupon_expires_at')
                """, Integer.class)).isZero();
    }
    @Test void reportsHealthyWithDatabase() throws Exception {
        var response = get("/actuator/health", true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }
    @Test void exposesPrometheusMetrics() throws Exception {
        assertThat(get("/actuator/prometheus", true).body()).contains("jvm_memory_used_bytes");
    }
    @Test void preparesExactlyOneEventAndItsInventoryOnRetry() {
        var c = campaign(5, "PERCENT", 10, null, 5000);
        var first = preparation.prepare(c.getId(), businessDate(clock.instant()), LocalTime.of(11,0), LocalTime.of(13,0), clock.instant());
        var repeated = preparation.prepare(c.getId(), first.getBusinessDate(), LocalTime.of(11,0), LocalTime.of(13,0), clock.instant());
        assertThat(repeated.getId()).isEqualTo(first.getId());
        assertThat(inventory.countByEventId(first.getId())).isEqualTo(5);
        assertThat(inventory.findByEventIdAndStatusOrderBySequenceNoAsc(first.getId(), CouponInventoryStatus.AVAILABLE,
                org.springframework.data.domain.PageRequest.of(0,10)).getContent())
                .extracting(i -> i.getSequenceNo()).containsExactly(1,2,3,4,5);
    }
    @Test void activationRequiresCompleteInventoryAndStillGatesElevenOClock() {
        var c = campaign(1, "AMOUNT", 1000, null, null);
        Instant start = Instant.parse("2026-09-23T02:00:00Z");
        clock.set(start.minusSeconds(60));
        var e = events.saveAndFlush(CouponEvent.scheduled(c.getId(), businessDate(start), 1, start,
                start.plusSeconds(3600), start, start.plusSeconds(7200), clock.instant()));
        assertCode(() -> lifecycle.activate(e.getId(), clock.instant()), CouponErrorCode.INVENTORY_NOT_AVAILABLE);
        loader.load(e.getId(), clock.instant());
        lifecycle.activate(e.getId(), clock.instant()); // prepared at 10:59
        long user = user();
        assertCode(() -> issue(e, user), CouponErrorCode.EVENT_NOT_ISSUABLE);
        clock.set(start);
        assertThat(issue(e, user).userCouponId()).isNotNull();
    }
    @Test void issuesUuidBinaryAndReturnsSameResultOnRepeat() {
        var event = activeEvent(2);
        long user = user();
        var first = issue(event, user);
        assertThat(issue(event, user)).isEqualTo(first);
        var stored = coupons.findById(first.userCouponId()).orElseThrow();
        assertThat(stored.getCouponCode()).isEqualTo(UUID.fromString(first.couponCode()));
        assertThat(stored.getQrVersion()).isZero();
        assertThat(stored.getQrToken()).isNull();
        assertThat(stored.getQrExpiresAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT OCTET_LENGTH(coupon_code) FROM user_coupon WHERE id = ?",
                Integer.class, first.userCouponId())).isEqualTo(16);
        assertThat(limits.findByUserIdAndLimitDate(user, businessDate(clock.instant())).orElseThrow().getIssuedCount()).isEqualTo(1);
    }
    @Test void limitsUserToThreeAcrossEventsAndRollsBackUnissuedInventory() {
        long user = user();
        var all = IntStream.range(0,4).mapToObj(i -> activeEvent(1)).toList();
        for (int i=0;i<3;i++) issue(all.get(i), user);
        assertCode(() -> issue(all.get(3),user), CouponErrorCode.DAILY_ISSUANCE_LIMIT_EXCEEDED);
        assertThat(inventory.countByEventIdAndStatus(all.get(3).getId(), CouponInventoryStatus.AVAILABLE)).isEqualTo(1);
    }
    @Test void soldOutAttemptDoesNotConsumeDailyLimit() {
        var event = activeEvent(1);
        issue(event, user());
        long nextUser = user();
        assertCode(() -> issue(event, nextUser), CouponErrorCode.COUPON_SOLD_OUT);
        assertThat(limits.findByUserIdAndLimitDate(nextUser, businessDate(clock.instant()))).isEmpty();
    }
    @Test void rollsBackInventoryAndDailyCountWhenCouponInsertFails() {
        var event = activeEvent(1);
        long user = user();
        // Fault injection: fails only in this disposable container, after inventory has been selected.
        jdbc.execute("ALTER TABLE user_coupon ADD CONSTRAINT ck_test_reject CHECK (user_id <> " + user + ")");
        try {
            assertThatThrownBy(() -> issue(event, user)).isInstanceOf(RuntimeException.class);
        } finally { jdbc.execute("ALTER TABLE user_coupon DROP CHECK ck_test_reject"); }
        assertThat(inventory.countByEventIdAndStatus(event.getId(), CouponInventoryStatus.AVAILABLE)).isEqualTo(1);
        assertThat(limits.findByUserIdAndLimitDate(user, businessDate(clock.instant()))).isEmpty();
    }
    @Test void lockedInventoryMeansRetryNotSoldOut() throws Exception {
        var event = activeEvent(1);
        long user = user();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                inventory.lockFirstAvailableByEventId(event.getId()).orElseThrow();
                locked.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("lock test timed out"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                assertCode(() -> issue(event, user), CouponErrorCode.INVENTORY_BUSY);
            } finally { release.countDown(); }
            pending.get(10, TimeUnit.SECONDS);
        }
        assertThat(issue(event,user).userCouponId()).isNotNull();
    }
    @Test void concurrentRequestsNeverOversell() throws Exception {
        var event = activeEvent(20);
        var tasks = IntStream.range(0,40).mapToObj(i -> {
            long user = user();
            return (Callable<Boolean>) () -> {
                for (int retry=0;retry<100;retry++) {
                    try { issue(event,user); return true; }
                    catch (CouponException e) {
                        if (e.getErrorCode() == CouponErrorCode.COUPON_SOLD_OUT) return false;
                        if (e.getErrorCode() != CouponErrorCode.INVENTORY_BUSY) throw e;
                        Thread.sleep(5);
                    }
                }
                throw new AssertionError("Retries exhausted");
            };
        }).toList();
        assertThat(parallel(tasks).stream().filter(Boolean::booleanValue).count()).isEqualTo(20);
        assertThat(coupons.countByEventId(event.getId())).isEqualTo(20);
        assertThat(inventory.countByEventIdAndStatus(event.getId(), CouponInventoryStatus.ISSUED)).isEqualTo(20);
    }
    @Test void concurrentSameUserIsIdempotent() throws Exception {
        var event = activeEvent(20);
        long user = user();
        var tasks = IntStream.range(0,20).mapToObj(i -> (Callable<Long>) () -> issue(event,user).userCouponId()).toList();
        assertThat(new HashSet<>(parallel(tasks))).hasSize(1);
        assertThat(limits.findByUserIdAndLimitDate(user, businessDate(clock.instant())).orElseThrow().getIssuedCount()).isEqualTo(1);
    }
    @Test void concurrentDifferentEventsRespectDailyLimit() throws Exception {
        long user = user();
        var tasks = IntStream.range(0,8).mapToObj(i -> {
            var event = activeEvent(1);
            return (Callable<Boolean>) () -> {
                try { issue(event,user); return true; }
                catch (CouponException e) {
                    assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.DAILY_ISSUANCE_LIMIT_EXCEEDED);
                    return false;
                }
            };
        }).toList();
        assertThat(parallel(tasks).stream().filter(Boolean::booleanValue).count()).isEqualTo(3);
    }
    @Test void countsEachKoreanDateEvenWhenUtcDateIsSame() {
        long user = user();
        clock.set(Instant.parse("2026-09-23T14:59:59Z"));
        for (int i=0;i<3;i++) issue(activeEvent(1), user);
        clock.set(Instant.parse("2026-09-23T15:00:00Z"));
        issue(activeEvent(1),user);
        assertThat(limits.findByUserIdAndLimitDate(user, LocalDate.of(2026,9,23)).orElseThrow().getIssuedCount()).isEqualTo(3);
        assertThat(limits.findByUserIdAndLimitDate(user, LocalDate.of(2026,9,24)).orElseThrow().getIssuedCount()).isEqualTo(1);
    }
    @Test void rotatesQrAndInvalidatesOldTokenThenRecordsUseOnce() {
        long user = user();
        var coupon = issue(activeEvent(1),user);
        assertCode(() -> access.rotateQr(coupon.userCouponId(), user()), CouponErrorCode.COUPON_NOT_FOUND);
        var first = access.rotateQr(coupon.userCouponId(),user);
        var second = access.rotateQr(coupon.userCouponId(),user);
        assertThat(second.qrToken()).isNotEqualTo(first.qrToken());
        assertThat(second.qrVersion()).isEqualTo(2);
        assertThat(second.expiresAt()).isEqualTo(clock.instant().plusSeconds(60));
        assertCode(() -> access.use(first.qrToken(), 1L, 2L, 10_000, null,null), CouponErrorCode.INVALID_QR);
        assertCode(() -> access.use(second.qrToken(), 999L, 2L, 10_000, null,null), CouponErrorCode.STORE_MISMATCH);
        var result = access.use(second.qrToken(), 1L, 2L, 10_000,null,null);
        assertThat(result.discountAmount()).isEqualTo(1000);
        assertCode(() -> access.use(second.qrToken(), 1L,2L,10_000,null,null), CouponErrorCode.INVALID_QR);
        assertThat(history.countByUserCouponId(coupon.userCouponId())).isEqualTo(1);
        assertThat(coupons.findById(coupon.userCouponId()).orElseThrow().getStatus()).isEqualTo(UserCouponStatus.USED);
        assertThat(access.detail(coupon.userCouponId(),user).usedAt()).isEqualTo(clock.instant());
        assertThatThrownBy(() -> history.saveAndFlush(CouponUsageHistory.used(coupon.userCouponId(),1000,clock.instant())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void rejectsQrAtSixtySecondBoundaryAndAllowsNewQr() {
        long user = user();
        var coupon = issue(activeEvent(1),user);
        var qr = access.rotateQr(coupon.userCouponId(),user);
        clock.set(qr.expiresAt());
        assertCode(() -> access.use(qr.qrToken(),1L,2L,10_000,null,null), CouponErrorCode.INVALID_QR);
        assertThat(access.rotateQr(coupon.userCouponId(),user).qrVersion()).isEqualTo(2);
        assertThat(history.count()).isZero();
    }
    @Test void capsQrAtCouponExpiryAndReportsEffectiveExpiredStatus() {
        var event = activeEvent(1);
        long user = user();
        var coupon = issue(event,user);
        clock.set(event.getUsableEndTime().minusSeconds(10));
        var qr = access.rotateQr(coupon.userCouponId(),user);
        assertThat(qr.expiresAt()).isEqualTo(event.getUsableEndTime());
        clock.set(event.getUsableEndTime());
        assertCode(() -> access.use(qr.qrToken(),1L,2L,10_000,null,null), CouponErrorCode.COUPON_NOT_USABLE);
        assertThat(access.detail(coupon.userCouponId(),user).coupon().status()).isEqualTo(UserCouponStatus.EXPIRED);
    }
    @Test void concurrentUseCreatesOnlyOneHistory() throws Exception {
        long user = user();
        var coupon = issue(activeEvent(1),user);
        var qr = access.rotateQr(coupon.userCouponId(),user);
        var tasks = IntStream.range(0,8).mapToObj(i -> (Callable<Boolean>) () -> {
            try { access.use(qr.qrToken(),1L,2L,10_000,null,null); return true; }
            catch (CouponException e) { assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.INVALID_QR); return false; }
        }).toList();
        assertThat(parallel(tasks).stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        assertThat(history.countByUserCouponId(coupon.userCouponId())).isEqualTo(1);
    }
    @Test void queryOnlyReturnsOwnedCouponsAndHttpEndpointsWork() throws Exception {
        long user = user(), other = user();
        var coupon = issue(activeEvent(1), user);
        assertThat(access.list(user,0,20).getTotalElements()).isEqualTo(1);
        assertThat(access.list(other,0,20).getTotalElements()).isZero();
        assertCode(() -> access.detail(coupon.userCouponId(),other), CouponErrorCode.COUPON_NOT_FOUND);
        var qr = post("/api/v1/user-coupons/" + coupon.userCouponId() + "/qr", "{\"userId\":" + user + "}");
        assertThat(qr.statusCode()).isEqualTo(200);
        assertThat(qr.body()).contains("\"qrVersion\":1", "\"qrToken\"");
        assertThat(get("/api/v1/users/" + user + "/coupons",false).body()).contains(coupon.couponCode());
        assertThat(get("/api/v1/users/" + user + "/coupons?size=101",false).statusCode()).isEqualTo(400);
    }
    @Test void menuDiscountUsesMatchingMenuAmountAndMinimumOrder() {
        var c = campaign(1,"PERCENT",20,77L,5000);
        var event = activate(c);
        long user = user();
        var qr = access.rotateQr(issue(event,user).userCouponId(),user);
        assertCode(() -> access.use(qr.qrToken(),1L,2L,4000,77L,2000),CouponErrorCode.INVALID_ORDER);
        assertCode(() -> access.use(qr.qrToken(),1L,2L,10_000,78L,4000),CouponErrorCode.INVALID_ORDER);
        assertThat(access.use(qr.qrToken(),1L,2L,10_000,77L,4000).discountAmount()).isEqualTo(800);
    }

    @Test void loadTestFixtureRecreatesNewSchemaDataAndPreservesUnrelatedEvent() {
        var ordinaryEvent = activeEvent(1);
        long ordinaryUser = user();
        issue(ordinaryEvent, ordinaryUser);
        var initializer = new io.github.minhyeok.coupon_prac.v1.loadtest.LoadTestFixtureInitializer(
                campaigns, events, lifecycle, inventory, loader, limits, coupons, jdbc, manager,
                true, Duration.ofMinutes(10));
        initializer.run(new org.springframework.boot.DefaultApplicationArguments());
        var fixtureEvents = events.findFixtures(
                io.github.minhyeok.coupon_prac.v1.loadtest.LoadTestFixtureInitializer.FIXTURE_MARKER);
        assertThat(fixtureEvents).hasSize(30);
        assertThat(inventory.countByEventIdIn(fixtureEvents.stream().map(CouponEvent::getId).toList())).isEqualTo(15000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE id BETWEEN 10000000 AND 10049999",
                Integer.class)).isEqualTo(50000);
        // Put a used coupon in the fixture, then ensure reset respects the history FK.
        var fixtureEvent = fixtureEvents.getFirst();
        clock.set(fixtureEvent.getIssueStartAt().plusSeconds(1));
        var issued = issue(fixtureEvent, 10_000_000L);
        var qr = access.rotateQr(issued.userCouponId(), 10_000_000L);
        var campaign = campaigns.findById(fixtureEvent.getCampaignId()).orElseThrow();
        access.use(qr.qrToken(), campaign.getStoreId(), campaign.getOwnerId(), 10_000, null, null);
        initializer.run(new org.springframework.boot.DefaultApplicationArguments());
        var controller = new io.github.minhyeok.coupon_prac.v1.loadtest.LoadTestFixtureController(
                events, inventory, limits, coupons);
        assertThat(controller.fixture().ready()).isTrue();
        assertThat(events.findById(ordinaryEvent.getId())).isPresent();
        assertThat(coupons.countByEventId(ordinaryEvent.getId())).isEqualTo(1);
        assertThat(limits.findByUserIdAndLimitDate(ordinaryUser, LocalDate.of(2026,9,23))
                .orElseThrow().getIssuedCount()).isEqualTo(1);
        assertThat(history.count()).isZero();
    }

    private long user() { return users.saveAndFlush(CouponUser.active(UUID.randomUUID().toString(), clock.instant())).getId(); }
    private Campaign campaign(int qty, String type, int value, Long menuId, Integer minimum) {
        return campaigns.saveAndFlush(Campaign.scheduled(1L,2L,qty,LocalTime.of(11,0),LocalTime.of(14,0),
                businessDate(clock.instant()), businessDate(clock.instant()).plusDays(1),type,value,menuId,minimum,10000,null,clock.instant()));
    }
    private CouponEvent activeEvent(int qty) { return activate(campaign(qty,"AMOUNT",1000,null,null)); }
    private CouponEvent activate(Campaign campaign) {
        Instant now = clock.instant();
        var event = events.saveAndFlush(CouponEvent.scheduled(campaign.getId(),businessDate(now),campaign.getIssueQuantity(),
                now.minusSeconds(60),now.plusSeconds(600),now.minusSeconds(60),now.plusSeconds(3600),now));
        loader.load(event.getId(),now);
        lifecycle.activate(event.getId(),now);
        return event;
    }
    private CouponIssueResult issue(CouponEvent event, long user) { return issuance.issue(new IssueCouponCommand(event.getId(),user)); }
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, CouponErrorCode code) {
        assertThatThrownBy(action).isInstanceOf(CouponException.class)
                .extracting(e -> ((CouponException)e).getErrorCode()).isEqualTo(code);
    }
    private <T> List<T> parallel(List<Callable<T>> tasks) throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            var pending = tasks.stream().map(task -> executor.submit(() -> { start.await(); return task.call(); })).toList();
            start.countDown();
            var result = new ArrayList<T>();
            for (var future : pending) result.add(future.get(30,TimeUnit.SECONDS));
            return result;
        }
    }
    private HttpResponse<String> get(String path, boolean management) throws Exception {
        int port = environment.getRequiredProperty(management ? "local.management.port" : "local.server.port",Integer.class);
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    private HttpResponse<String> post(String path, String json) throws Exception {
        int port = environment.getRequiredProperty("local.server.port",Integer.class);
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("Content-Type","application/json").timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
