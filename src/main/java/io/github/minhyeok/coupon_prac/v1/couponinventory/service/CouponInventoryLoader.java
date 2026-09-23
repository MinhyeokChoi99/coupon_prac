package io.github.minhyeok.coupon_prac.v1.couponinventory.service;
import java.time.Instant;
import io.github.minhyeok.coupon_prac.v1.couponevent.entity.CouponEventStatus;
import io.github.minhyeok.coupon_prac.v1.couponevent.repository.CouponEventRepository;
import io.github.minhyeok.coupon_prac.v1.couponinventory.entity.CouponInventory;
import io.github.minhyeok.coupon_prac.v1.couponinventory.repository.CouponInventoryRepository;
import io.github.minhyeok.coupon_prac.v1.common.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @RequiredArgsConstructor
public class CouponInventoryLoader {
    private final CouponEventRepository events;
    private final CouponInventoryRepository inventory;
    @Transactional
    public void load(Long eventId, Instant now) {
        // Only preparation locks the event. Issuance never updates a shared quantity row.
        var event = events.lockById(eventId).orElseThrow(() -> new CouponException(CouponErrorCode.EVENT_NOT_FOUND));
        long existing = inventory.countByEventId(eventId);
        if (existing == event.getCouponQuantity()) return;
        if (existing != 0 || event.getStatus() != CouponEventStatus.SCHEDULED)
            throw new CouponException(CouponErrorCode.INVENTORY_NOT_AVAILABLE);
        // All rows commit together. A failed load leaves zero rows, so retries do not skip sequence holes.
        for (int sequence = 1; sequence <= event.getCouponQuantity(); sequence++) {
            inventory.save(CouponInventory.available(event, sequence, now));
            if (sequence % 1000 == 0) inventory.flush();
        }
        inventory.flush();
    }
}
