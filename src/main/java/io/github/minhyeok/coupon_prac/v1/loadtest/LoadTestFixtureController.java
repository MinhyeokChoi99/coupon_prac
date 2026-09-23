package io.github.minhyeok.coupon_prac.v1.loadtest;

import java.util.List;

import io.github.minhyeok.coupon_prac.v1.couponevent.entity.CouponEventStatus;
import io.github.minhyeok.coupon_prac.v1.couponevent.repository.CouponEventRepository;
import io.github.minhyeok.coupon_prac.v1.couponinventory.entity.CouponInventoryStatus;
import io.github.minhyeok.coupon_prac.v1.couponinventory.repository.CouponInventoryRepository;
import io.github.minhyeok.coupon_prac.v1.usercoupon.repository.UserCouponDailyLimitRepository;
import io.github.minhyeok.coupon_prac.v1.usercoupon.repository.UserCouponRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("loadtest")
@RequestMapping("/internal/load-test")
public class LoadTestFixtureController {

    private final CouponEventRepository couponEventRepository;
    private final CouponInventoryRepository couponInventoryRepository;
    private final UserCouponDailyLimitRepository userCouponDailyLimitRepository;
    private final UserCouponRepository userCouponRepository;

    public LoadTestFixtureController(
            CouponEventRepository couponEventRepository,
            CouponInventoryRepository couponInventoryRepository,
            UserCouponDailyLimitRepository userCouponDailyLimitRepository,
            UserCouponRepository userCouponRepository
    ) {
        this.couponEventRepository = couponEventRepository;
        this.couponInventoryRepository = couponInventoryRepository;
        this.userCouponDailyLimitRepository = userCouponDailyLimitRepository;
        this.userCouponRepository = userCouponRepository;
    }

    @GetMapping("/coupon-events")
    public List<LoadTestCouponEventResponse> couponEvents() {
        return loadTestEvents()
                .stream()
                .filter(event -> event.getStatus() == CouponEventStatus.ACTIVE)
                .map(event -> new LoadTestCouponEventResponse(event.getId(), "load-test-event-" + event.getId()))
                .toList();
    }

    @GetMapping("/fixture")
    public LoadTestFixtureStatusResponse fixture() {
        List<Long> eventIds = loadTestEvents().stream()
                .map(event -> event.getId())
                .toList();

        long totalInventory = couponInventoryRepository.countByEventIdIn(eventIds);
        long availableInventory = couponInventoryRepository.countByEventIdInAndStatus(
                eventIds,
                CouponInventoryStatus.AVAILABLE
        );
        long issuedInventory = couponInventoryRepository.countByEventIdInAndStatus(
                eventIds,
                CouponInventoryStatus.ISSUED
        );
        long issuedUserCoupon = userCouponRepository.countByEventIdIn(eventIds);
        long dailyLimitRows = userCouponDailyLimitRepository.countByUserIdBetween(
                LoadTestFixtureInitializer.USER_ID_START,
                LoadTestFixtureInitializer.USER_ID_END
        );
        boolean ready = eventIds.size() == 30
                && totalInventory == 15_000
                && availableInventory == 15_000
                && issuedInventory == 0
                && issuedUserCoupon == 0
                && dailyLimitRows == 0;

        return new LoadTestFixtureStatusResponse(
                eventIds.size(),
                totalInventory,
                availableInventory,
                issuedInventory,
                issuedUserCoupon,
                dailyLimitRows,
                ready
        );
    }

    @GetMapping("/result")
    public LoadTestResultResponse result() {
        List<LoadTestEventResult> events = loadTestEvents().stream()
                .map(event -> new LoadTestCouponEventResponse(event.getId(), "load-test-event-" + event.getId()))
                .map(event -> {
                    long issuedInventory = couponInventoryRepository.countByEventIdAndStatus(
                            event.id(),
                            CouponInventoryStatus.ISSUED
                    );
                    long issuedUserCoupon = userCouponRepository.countByEventId(event.id());
                    boolean passed = issuedInventory == 500 && issuedUserCoupon == 500;
                    return new LoadTestEventResult(event.id(), event.name(), issuedInventory, issuedUserCoupon, passed);
                })
                .toList();

        long issuedInventoryTotal = events.stream().mapToLong(LoadTestEventResult::issuedInventory).sum();
        long issuedUserCouponTotal = events.stream().mapToLong(LoadTestEventResult::issuedUserCoupon).sum();
        boolean passed = events.size() == 30
                && issuedInventoryTotal == 15_000
                && issuedUserCouponTotal == 15_000
                && events.stream().allMatch(LoadTestEventResult::passed);

        return new LoadTestResultResponse(issuedInventoryTotal, issuedUserCouponTotal, passed, events);
    }

    public record LoadTestCouponEventResponse(Long id, String name) {
    }

    public record LoadTestFixtureStatusResponse(
            int eventCount,
            long totalInventory,
            long availableInventory,
            long issuedInventory,
            long issuedUserCoupon,
            long dailyLimitRows,
            boolean ready
    ) {
    }

    public record LoadTestEventResult(
            Long eventId,
            String eventName,
            long issuedInventory,
            long issuedUserCoupon,
            boolean passed
    ) {
    }

    public record LoadTestResultResponse(
            long issuedInventoryTotal,
            long issuedUserCouponTotal,
            boolean passed,
            List<LoadTestEventResult> events
    ) {
    }

    private List<io.github.minhyeok.coupon_prac.v1.couponevent.entity.CouponEvent> loadTestEvents() {
        return couponEventRepository.findFixtures(LoadTestFixtureInitializer.FIXTURE_MARKER);
    }
}
