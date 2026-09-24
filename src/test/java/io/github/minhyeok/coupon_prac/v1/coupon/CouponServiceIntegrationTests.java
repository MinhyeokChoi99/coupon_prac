package io.github.minhyeok.coupon_prac.v1.coupon;

import static org.assertj.core.api.Assertions.*;

import io.github.minhyeok.coupon_prac.v1.coupon.dto.*;
import io.github.minhyeok.coupon_prac.v1.coupon.entity.*;
import io.github.minhyeok.coupon_prac.v1.coupon.exception.*;
import io.github.minhyeok.coupon_prac.v1.coupon.repository.*;
import io.github.minhyeok.coupon_prac.v1.coupon.service.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

/** MySQL에서 사전 적재·동시 발급·QR 사용을 검증한다. 시간 경계는 데이터와 명시적인 시각 값으로 재현한다. */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
class CouponServiceIntegrationTests {
    @Autowired org.springframework.context.ApplicationContext context;

    @Container @ServiceConnection
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired CouponService service;
    @Autowired CampaignRepository campaigns;
    @Autowired CouponUserRepository users;
    @Autowired CouponEventRepository events;
    @Autowired CouponInventoryRepository inventory;
    @Autowired UserCouponRepository coupons;
    @Autowired UserCouponDailyLimitRepository limits;
    @Autowired CouponUsageHistoryRepository history;
    @Autowired PlatformTransactionManager manager;

    @BeforeEach
    void resetDisposableTestDatabase() {
        for (String table :
                List.of(
                        "coupon_usage_history",
                        "user_coupon",
                        "coupon_daily_limit",
                        "coupon_inventory",
                        "coupon_event",
                        "campaign",
                        "`user`")) jdbc.update("DELETE FROM " + table);
    }

    @Test
    void validatesCurrentSchemaAndUtcSession() {
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class))
                .isEqualTo("+00:00");
        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
                                AND table_name IN ('campaign','coupon_event','coupon_inventory','user','coupon_daily_limit','user_coupon','coupon_usage_history')
                                """,
                                Integer.class))
                .isEqualTo(7);
        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
                                AND table_name = 'coupon_event' AND column_name IN ('name','total_quantity','coupon_expires_at')
                                """,
                                Integer.class))
                .isZero();
    }

    @Test
    void doesNotRegisterStartupDataWriters() {
        assertThat(context.getBeansOfType(org.springframework.boot.ApplicationRunner.class))
                .isEmpty();
        assertThat(context.getBeansOfType(org.springframework.boot.CommandLineRunner.class))
                .isEmpty();
    }

    @Test
    void reportsHealthyWithDatabase() throws Exception {
        HttpResponse<String> response = get("/actuator/health", true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void exposesPrometheusMetrics() throws Exception {
        assertThat(get("/actuator/prometheus", true).body()).contains("jvm_memory_used_bytes");
    }

    @Test
    void preparesExactlyOneEventAndItsInventoryOnRetry() {
        Campaign c = campaign(5, "PERCENT", 10, null, 5000);
        CouponEvent first =
                service.prepareEvent(
                        c.getId(),
                        Instant.now().atZone(ZoneId.of("Asia/Seoul")).toLocalDate(),
                        LocalTime.of(11, 0),
                        LocalTime.of(13, 0),
                        Instant.now());
        CouponEvent repeated =
                service.prepareEvent(
                        c.getId(),
                        first.getBusinessDate(),
                        LocalTime.of(11, 0),
                        LocalTime.of(13, 0),
                        Instant.now());
        assertThat(repeated.getId()).isEqualTo(first.getId());
        assertThat(inventory.countByEventId(first.getId())).isEqualTo(5);
        assertThat(
                        inventory
                                .findByEventIdAndStatusOrderBySequenceNoAsc(
                                        first.getId(),
                                        CouponInventoryStatus.AVAILABLE,
                                        org.springframework.data.domain.PageRequest.of(0, 10))
                                .getContent())
                .extracting(i -> i.getSequenceNo())
                .containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void preparationAndInternalInventoryLoadRollBackTogether() {
        Campaign campaign = campaign(3, "AMOUNT", 1000, null, null);
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.executeWithoutResult(
                status -> {
                    CouponEvent event =
                            service.prepareEvent(
                                    campaign.getId(),
                                    Instant.now().atZone(ZoneId.of("Asia/Seoul")).toLocalDate(),
                                    LocalTime.of(11, 0),
                                    LocalTime.of(13, 0),
                                    Instant.now());
                    assertThat(inventory.countByEventId(event.getId())).isEqualTo(3);
                    status.setRollbackOnly();
                });
        assertThat(events.count()).isZero();
        assertThat(inventory.count()).isZero();
        assertThat(campaigns.count()).isEqualTo(1);
    }

    @Test
    void issuanceCommitsIndependentlyOfCallerTransaction() {
        CouponEvent event = activeEvent(1);
        long user = user();
        TransactionTemplate callerTransaction = new TransactionTemplate(manager);
        CouponIssueResult result =
                callerTransaction.execute(
                        status -> {
                            CouponIssueResult issued = issue(event, user);
                            status.setRollbackOnly();
                            return issued;
                        });
        assertThat(result).isNotNull();
        assertThat(coupons.findById(result.userCouponId())).isPresent();
        assertThat(inventory.countByEventIdAndStatus(event.getId(), CouponInventoryStatus.ISSUED))
                .isEqualTo(1);
        assertThat(
                        limits.findByUserIdAndLimitDate(
                                        user,
                                        Instant.now().atZone(ZoneId.of("Asia/Seoul")).toLocalDate())
                                .orElseThrow()
                                .getIssuedCount())
                .isEqualTo(1);
    }

    @Test
    void preloadedActiveEventStillChecksIssueTime() {
        Campaign campaign = campaign(1, "AMOUNT", 1000, null, null);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant start = now.plusSeconds(3600);
        CouponEvent event =
                events.saveAndFlush(
                        CouponEvent.active(
                                campaign.getId(),
                                start.atZone(ZoneId.of("Asia/Seoul")).toLocalDate(),
                                1,
                                start,
                                start.plusSeconds(3600),
                                start,
                                start.plusSeconds(7200),
                                now));
        service.loadInventory(event.getId(), now);
        long user = user();
        assertThat(event.getStatus()).isEqualTo(CouponEventStatus.ACTIVE);
        assertThat(event.isIssuableAt(start.minusNanos(1))).isFalse();
        assertThat(event.isIssuableAt(start)).isTrue();
        assertThat(event.isIssuableAt(event.getIssueEndAt())).isFalse();
        assertCode(() -> issue(event, user), CouponErrorCode.EVENT_NOT_ISSUABLE);
        jdbc.update(
                "UPDATE coupon_event SET issue_start_at = ? WHERE id = ?",
                LocalDateTime.ofInstant(now.minusSeconds(60), ZoneOffset.UTC),
                event.getId());
        assertThat(issue(event, user).userCouponId()).isNotNull();
    }

    @Test
    void issuesUuidBinaryAndReturnsSameResultOnRepeat() {
        CouponEvent event = activeEvent(2);
        long user = user();
        CouponIssueResult first = issue(event, user);
        assertThat(issue(event, user)).isEqualTo(first);
        UserCoupon stored = coupons.findById(first.userCouponId()).orElseThrow();
        assertThat(stored.getCouponCode()).isEqualTo(UUID.fromString(first.couponCode()));
        assertThat(stored.getQrVersion()).isZero();
        assertThat(stored.getQrToken()).isNull();
        assertThat(stored.getQrExpiresAt()).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT OCTET_LENGTH(coupon_code) FROM user_coupon WHERE id = ?",
                                Integer.class,
                                first.userCouponId()))
                .isEqualTo(16);
        assertThat(
                        limits.findByUserIdAndLimitDate(
                                        user,
                                        Instant.now().atZone(ZoneId.of("Asia/Seoul")).toLocalDate())
                                .orElseThrow()
                                .getIssuedCount())
                .isEqualTo(1);
    }

    @Test
    void limitsUserToThreeAcrossEventsAndRollsBackUnissuedInventory() {
        long user = user();
        List<CouponEvent> all = IntStream.range(0, 4).mapToObj(i -> activeEvent(1)).toList();
        for (int i = 0; i < 3; i++) issue(all.get(i), user);
        assertCode(() -> issue(all.get(3), user), CouponErrorCode.DAILY_ISSUANCE_LIMIT_EXCEEDED);
        assertThat(
                        inventory.countByEventIdAndStatus(
                                all.get(3).getId(), CouponInventoryStatus.AVAILABLE))
                .isEqualTo(1);
    }

    @Test
    void soldOutAttemptDoesNotConsumeDailyLimit() {
        CouponEvent event = activeEvent(1);
        issue(event, user());
        long nextUser = user();
        assertCode(() -> issue(event, nextUser), CouponErrorCode.COUPON_SOLD_OUT);
        assertThat(
                        limits.findByUserIdAndLimitDate(
                                nextUser,
                                Instant.now().atZone(ZoneId.of("Asia/Seoul")).toLocalDate()))
                .isEmpty();
    }

    @Test
    void rollsBackInventoryAndDailyCountWhenCouponInsertFails() {
        CouponEvent event = activeEvent(1);
        long user = user();
        // Fault injection: fails only in this disposable container, after inventory has been
        // selected.
        jdbc.execute(
                "ALTER TABLE user_coupon ADD CONSTRAINT ck_test_reject CHECK (user_id <> "
                        + user
                        + ")");
        try {
            assertThatThrownBy(() -> issue(event, user)).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("ALTER TABLE user_coupon DROP CHECK ck_test_reject");
        }
        assertThat(
                        inventory.countByEventIdAndStatus(
                                event.getId(), CouponInventoryStatus.AVAILABLE))
                .isEqualTo(1);
        assertThat(
                        limits.findByUserIdAndLimitDate(
                                user, Instant.now().atZone(ZoneId.of("Asia/Seoul")).toLocalDate()))
                .isEmpty();
    }

    @Test
    void lockedInventoryMeansRetryNotSoldOut() throws Exception {
        CouponEvent event = activeEvent(1);
        long user = user();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> pending =
                    executor.submit(
                            () ->
                                    new TransactionTemplate(manager)
                                            .executeWithoutResult(
                                                    tx -> {
                                                        inventory
                                                                .lockFirstAvailableByEventId(
                                                                        event.getId())
                                                                .orElseThrow();
                                                        locked.countDown();
                                                        try {
                                                            if (!release.await(
                                                                    10, TimeUnit.SECONDS))
                                                                throw new AssertionError(
                                                                        "lock test timed out");
                                                        } catch (InterruptedException e) {
                                                            throw new RuntimeException(e);
                                                        }
                                                    }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                assertCode(() -> issue(event, user), CouponErrorCode.INVENTORY_BUSY);
            } finally {
                release.countDown();
            }
            pending.get(10, TimeUnit.SECONDS);
        }
        assertThat(issue(event, user).userCouponId()).isNotNull();
    }

    @Test
    void concurrentRequestsNeverOversell() throws Exception {
        CouponEvent event = activeEvent(20);
        List<Callable<Boolean>> tasks =
                IntStream.range(0, 40)
                        .mapToObj(
                                i -> {
                                    long user = user();
                                    return (Callable<Boolean>)
                                            () -> {
                                                for (int retry = 0; retry < 100; retry++) {
                                                    try {
                                                        issue(event, user);
                                                        return true;
                                                    } catch (CouponException e) {
                                                        if (e.getErrorCode()
                                                                == CouponErrorCode.COUPON_SOLD_OUT)
                                                            return false;
                                                        if (e.getErrorCode()
                                                                != CouponErrorCode.INVENTORY_BUSY)
                                                            throw e;
                                                        Thread.sleep(5);
                                                    }
                                                }
                                                throw new AssertionError("Retries exhausted");
                                            };
                                })
                        .toList();
        assertThat(parallel(tasks).stream().filter(Boolean::booleanValue).count()).isEqualTo(20);
        assertThat(coupons.countByEventId(event.getId())).isEqualTo(20);
        assertThat(inventory.countByEventIdAndStatus(event.getId(), CouponInventoryStatus.ISSUED))
                .isEqualTo(20);
    }

    @Test
    void concurrentSameUserIsIdempotent() throws Exception {
        CouponEvent event = activeEvent(20);
        long user = user();
        List<Callable<Long>> tasks =
                IntStream.range(0, 20)
                        .mapToObj(i -> (Callable<Long>) () -> issue(event, user).userCouponId())
                        .toList();
        assertThat(new HashSet<>(parallel(tasks))).hasSize(1);
        assertThat(
                        limits.findByUserIdAndLimitDate(
                                        user,
                                        Instant.now().atZone(ZoneId.of("Asia/Seoul")).toLocalDate())
                                .orElseThrow()
                                .getIssuedCount())
                .isEqualTo(1);
    }

    @Test
    void concurrentDifferentEventsRespectDailyLimit() throws Exception {
        long user = user();
        List<Callable<Boolean>> tasks =
                IntStream.range(0, 8)
                        .mapToObj(
                                i -> {
                                    CouponEvent event = activeEvent(1);
                                    return (Callable<Boolean>)
                                            () -> {
                                                try {
                                                    issue(event, user);
                                                    return true;
                                                } catch (CouponException e) {
                                                    assertThat(e.getErrorCode())
                                                            .isEqualTo(
                                                                    CouponErrorCode
                                                                            .DAILY_ISSUANCE_LIMIT_EXCEEDED);
                                                    return false;
                                                }
                                            };
                                })
                        .toList();
        assertThat(parallel(tasks).stream().filter(Boolean::booleanValue).count()).isEqualTo(3);
    }

    @Test
    void countsEachKoreanDateEvenWhenUtcDateIsSame() {
        long user = user();
        Instant beforeMidnight = Instant.parse("2026-09-23T14:59:59Z");
        Instant afterMidnight = Instant.parse("2026-09-23T15:00:00Z");
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.executeWithoutResult(
                tx -> {
                    limits.createIfAbsent(user, beforeMidnight);
                    for (int count = 0; count < 3; count++) {
                        assertThat(
                                        limits.incrementIfBelowLimit(
                                                user,
                                                beforeMidnight
                                                        .atZone(ZoneId.of("Asia/Seoul"))
                                                        .toLocalDate(),
                                                beforeMidnight))
                                .isEqualTo(1);
                    }
                    assertThat(
                                    limits.incrementIfBelowLimit(
                                            user,
                                            beforeMidnight
                                                    .atZone(ZoneId.of("Asia/Seoul"))
                                                    .toLocalDate(),
                                            beforeMidnight))
                            .isZero();
                    limits.createIfAbsent(user, afterMidnight);
                    assertThat(
                                    limits.incrementIfBelowLimit(
                                            user,
                                            afterMidnight
                                                    .atZone(ZoneId.of("Asia/Seoul"))
                                                    .toLocalDate(),
                                            afterMidnight))
                            .isEqualTo(1);
                });
        assertThat(
                        limits.findByUserIdAndLimitDate(user, LocalDate.of(2026, 9, 23))
                                .orElseThrow()
                                .getIssuedCount())
                .isEqualTo(3);
        assertThat(
                        limits.findByUserIdAndLimitDate(user, LocalDate.of(2026, 9, 24))
                                .orElseThrow()
                                .getIssuedCount())
                .isEqualTo(1);
    }

    @Test
    void rotatesQrAndInvalidatesOldTokenThenRecordsUseOnce() {
        long user = user();
        CouponIssueResult coupon = issue(activeEvent(1), user);
        assertCode(
                () -> service.rotateQr(coupon.userCouponId(), user()),
                CouponErrorCode.COUPON_NOT_FOUND);
        QrResult first = service.rotateQr(coupon.userCouponId(), user);
        Instant beforeRotation = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        QrResult second = service.rotateQr(coupon.userCouponId(), user);
        Instant afterRotation = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        assertThat(second.qrToken()).isNotEqualTo(first.qrToken());
        assertThat(second.qrVersion()).isEqualTo(2);
        assertThat(second.expiresAt())
                .isBetween(beforeRotation.plusSeconds(60), afterRotation.plusSeconds(60));
        assertCode(
                () -> service.use(first.qrToken(), 1L, 2L, 10_000, null, null),
                CouponErrorCode.INVALID_QR);
        assertCode(
                () -> service.use(second.qrToken(), 999L, 2L, 10_000, null, null),
                CouponErrorCode.STORE_MISMATCH);
        UseResult result = service.use(second.qrToken(), 1L, 2L, 10_000, null, null);
        assertThat(result.discountAmount()).isEqualTo(1000);
        assertCode(
                () -> service.use(second.qrToken(), 1L, 2L, 10_000, null, null),
                CouponErrorCode.INVALID_QR);
        assertThat(history.countByUserCouponId(coupon.userCouponId())).isEqualTo(1);
        assertThat(coupons.findById(coupon.userCouponId()).orElseThrow().getStatus())
                .isEqualTo(UserCouponStatus.USED);
        assertThat(service.detail(coupon.userCouponId(), user).usedAt()).isEqualTo(result.usedAt());
        assertThatThrownBy(
                        () ->
                                history.saveAndFlush(
                                        CouponUsageHistory.used(
                                                coupon.userCouponId(), 1000, Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsExpiredQrAndAllowsNewQr() {
        long user = user();
        CouponIssueResult coupon = issue(activeEvent(1), user);
        QrResult qr = service.rotateQr(coupon.userCouponId(), user);
        UserCoupon stored = coupons.findById(coupon.userCouponId()).orElseThrow();
        stored.requireValidQr(qr.qrToken(), qr.expiresAt().minusNanos(1));
        assertCode(
                () -> stored.requireValidQr(qr.qrToken(), qr.expiresAt()),
                CouponErrorCode.INVALID_QR);
        jdbc.update(
                "UPDATE user_coupon SET qr_expires_at = ? WHERE id = ?",
                LocalDateTime.ofInstant(Instant.now().minusSeconds(1), ZoneOffset.UTC),
                coupon.userCouponId());
        assertCode(
                () -> service.use(qr.qrToken(), 1L, 2L, 10_000, null, null),
                CouponErrorCode.INVALID_QR);
        assertThat(service.rotateQr(coupon.userCouponId(), user).qrVersion()).isEqualTo(2);
        assertThat(history.count()).isZero();
    }

    @Test
    void capsQrAtCouponExpiryAndReportsEffectiveExpiredStatus() {
        CouponEvent event = activeEvent(1);
        long user = user();
        CouponIssueResult coupon = issue(event, user);
        Instant useEnd = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(30);
        jdbc.update(
                "UPDATE user_coupon SET usable_end_time = ? WHERE id = ?",
                LocalDateTime.ofInstant(useEnd, ZoneOffset.UTC),
                coupon.userCouponId());
        QrResult qr = service.rotateQr(coupon.userCouponId(), user);
        assertThat(qr.expiresAt()).isEqualTo(useEnd);
        UserCoupon stored = coupons.findById(coupon.userCouponId()).orElseThrow();
        assertCode(() -> stored.requireUsable(useEnd), CouponErrorCode.COUPON_NOT_USABLE);
        jdbc.update(
                "UPDATE user_coupon SET usable_end_time = ? WHERE id = ?",
                LocalDateTime.ofInstant(Instant.now().minusSeconds(1), ZoneOffset.UTC),
                coupon.userCouponId());
        assertCode(
                () -> service.use(qr.qrToken(), 1L, 2L, 10_000, null, null),
                CouponErrorCode.COUPON_NOT_USABLE);
        assertThat(service.detail(coupon.userCouponId(), user).coupon().status())
                .isEqualTo(UserCouponStatus.EXPIRED);
    }

    @Test
    void concurrentUseCreatesOnlyOneHistory() throws Exception {
        long user = user();
        CouponIssueResult coupon = issue(activeEvent(1), user);
        QrResult qr = service.rotateQr(coupon.userCouponId(), user);
        List<Callable<Boolean>> tasks =
                IntStream.range(0, 8)
                        .mapToObj(
                                i ->
                                        (Callable<Boolean>)
                                                () -> {
                                                    try {
                                                        service.use(
                                                                qr.qrToken(),
                                                                1L,
                                                                2L,
                                                                10_000,
                                                                null,
                                                                null);
                                                        return true;
                                                    } catch (CouponException e) {
                                                        assertThat(e.getErrorCode())
                                                                .isEqualTo(
                                                                        CouponErrorCode.INVALID_QR);
                                                        return false;
                                                    }
                                                })
                        .toList();
        assertThat(parallel(tasks).stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        assertThat(history.countByUserCouponId(coupon.userCouponId())).isEqualTo(1);
    }

    @Test
    void queryOnlyReturnsOwnedCouponsAndHttpEndpointsWork() throws Exception {
        long user = user(), other = user();
        CouponIssueResult coupon = issue(activeEvent(1), user);
        assertThat(service.list(user, 0, 20).getTotalElements()).isEqualTo(1);
        assertThat(service.list(other, 0, 20).getTotalElements()).isZero();
        assertCode(
                () -> service.detail(coupon.userCouponId(), other),
                CouponErrorCode.COUPON_NOT_FOUND);
        HttpResponse<String> qr =
                post(
                        "/api/v1/user-coupons/" + coupon.userCouponId() + "/qr",
                        "{\"userId\":" + user + "}");
        assertThat(qr.statusCode()).isEqualTo(200);
        assertThat(qr.body()).contains("\"qrVersion\":1", "\"qrToken\"");
        assertThat(get("/api/v1/users/" + user + "/coupons", false).body())
                .contains(coupon.couponCode());
        assertThat(get("/api/v1/users/" + user + "/coupons?size=101", false).statusCode())
                .isEqualTo(400);
    }

    @Test
    void unifiedControllerPreservesIssueUseAndDetailRoutes() throws Exception {
        CouponEvent event = activeEvent(1);
        long user = user();
        HttpResponse<String> issued =
                post(
                        "/api/v1/coupon-events/" + event.getId() + "/coupons",
                        "{\"userId\":" + user + "}");
        assertThat(issued.statusCode()).isEqualTo(201);
        UserCoupon coupon = coupons.findByEventIdAndUserId(event.getId(), user).orElseThrow();
        assertThat(issued.body()).contains(coupon.getCouponCode().toString());
        assertThat(
                        post(
                                        "/api/v1/coupon-events/" + event.getId() + "/coupons",
                                        "{\"userId\":" + user + "}")
                                .body())
                .isEqualTo(issued.body());
        assertThat(
                        post(
                                        "/api/v1/user-coupons/" + coupon.getId() + "/qr",
                                        "{\"userId\":" + user + "}")
                                .statusCode())
                .isEqualTo(200);
        UUID token = coupons.findById(coupon.getId()).orElseThrow().getQrToken();
        HttpResponse<String> used =
                post(
                        "/api/v1/coupon-usages",
                        "{\"qrToken\":\""
                                + token
                                + "\",\"storeId\":1,\"ownerId\":2,\"orderAmount\":10000}");
        assertThat(used.statusCode()).isEqualTo(200);
        assertThat(used.body()).contains("\"discountAmount\":1000");
        HttpResponse<String> detail =
                get("/api/v1/user-coupons/" + coupon.getId() + "?userId=" + user, false);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).contains("\"status\":\"USED\"", "\"discountAmount\":1000");
        assertThat(
                        get("/api/v1/user-coupons/" + coupon.getId() + "?userId=" + user(), false)
                                .statusCode())
                .isEqualTo(404);
    }

    @Test
    void menuDiscountUsesMatchingMenuAmountAndMinimumOrder() {
        Campaign c = campaign(1, "PERCENT", 20, 77L, 5000);
        CouponEvent event = activate(c);
        long user = user();
        QrResult qr = service.rotateQr(issue(event, user).userCouponId(), user);
        assertCode(
                () -> service.use(qr.qrToken(), 1L, 2L, 4000, 77L, 2000),
                CouponErrorCode.INVALID_ORDER);
        assertCode(
                () -> service.use(qr.qrToken(), 1L, 2L, 10_000, 78L, 4000),
                CouponErrorCode.INVALID_ORDER);
        assertThat(service.use(qr.qrToken(), 1L, 2L, 10_000, 77L, 4000).discountAmount())
                .isEqualTo(800);
    }

    private long user() {
        return users.saveAndFlush(CouponUser.active(UUID.randomUUID().toString(), Instant.now()))
                .getId();
    }

    private Campaign campaign(int qty, String type, int value, Long menuId, Integer minimum) {
        return campaigns.saveAndFlush(
                Campaign.active(
                        1L,
                        2L,
                        qty,
                        LocalTime.of(11, 0),
                        LocalTime.of(14, 0),
                        Instant.now().atZone(ZoneId.of("Asia/Seoul")).toLocalDate(),
                        Instant.now().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().plusDays(1),
                        type,
                        value,
                        menuId,
                        minimum,
                        10000,
                        null,
                        Instant.now()));
    }

    private CouponEvent activeEvent(int qty) {
        return activate(campaign(qty, "AMOUNT", 1000, null, null));
    }

    private CouponEvent activate(Campaign campaign) {
        Instant now = Instant.now();
        CouponEvent event =
                events.saveAndFlush(
                        CouponEvent.active(
                                campaign.getId(),
                                now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate(),
                                campaign.getIssueQuantity(),
                                now.minusSeconds(60),
                                now.plusSeconds(600),
                                now.minusSeconds(60),
                                now.plusSeconds(3600),
                                now));
        service.loadInventory(event.getId(), now);
        return event;
    }

    private CouponIssueResult issue(CouponEvent event, long user) {
        return service.issue(new IssueCouponCommand(event.getId(), user));
    }

    private void assertCode(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action, CouponErrorCode code) {
        assertThatThrownBy(action)
                .isInstanceOf(CouponException.class)
                .extracting(e -> ((CouponException) e).getErrorCode())
                .isEqualTo(code);
    }

    private <T> List<T> parallel(List<Callable<T>> tasks) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> pending =
                    tasks.stream()
                            .map(
                                    task ->
                                            executor.submit(
                                                    () -> {
                                                        start.await();
                                                        return task.call();
                                                    }))
                            .toList();
            start.countDown();
            List<T> result = new ArrayList<T>();
            for (Future<T> future : pending) result.add(future.get(30, TimeUnit.SECONDS));
            return result;
        }
    }

    private HttpResponse<String> get(String path, boolean management) throws Exception {
        int port =
                environment.getRequiredProperty(
                        management ? "local.management.port" : "local.server.port", Integer.class);
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        int port = environment.getRequiredProperty("local.server.port", Integer.class);
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(5))
                            .POST(HttpRequest.BodyPublishers.ofString(json))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
