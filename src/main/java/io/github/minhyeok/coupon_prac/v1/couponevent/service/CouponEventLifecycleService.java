package io.github.minhyeok.coupon_prac.v1.couponevent.service;
import java.time.Instant;
import io.github.minhyeok.coupon_prac.v1.couponevent.entity.CouponEventStatus;
import io.github.minhyeok.coupon_prac.v1.couponevent.repository.CouponEventRepository;
import io.github.minhyeok.coupon_prac.v1.couponinventory.repository.CouponInventoryRepository;
import io.github.minhyeok.coupon_prac.v1.common.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @RequiredArgsConstructor
public class CouponEventLifecycleService {
    private final CouponEventRepository events;
    private final CouponInventoryRepository inventory;
    @Transactional
    public void activate(Long eventId, Instant now) {
        var event = events.lockById(eventId).orElseThrow(() -> new CouponException(CouponErrorCode.EVENT_NOT_FOUND));
        if (event.getStatus() == CouponEventStatus.ACTIVE) return;
        if (inventory.countByEventId(eventId) != event.getCouponQuantity())
            throw new CouponException(CouponErrorCode.INVENTORY_NOT_AVAILABLE);
        event.activate(now);
    }
    @Transactional
    public void closeEventsDue(Instant now) {
        for (var status : java.util.List.of(CouponEventStatus.SCHEDULED, CouponEventStatus.ACTIVE,
                CouponEventStatus.PAUSED, CouponEventStatus.SOLD_OUT)) {
            for (var event : events.findByStatusAndIssueEndAtLessThanEqual(status, now)) {
                events.lockById(event.getId()).orElseThrow().end(now);
            }
        }
    }
}
