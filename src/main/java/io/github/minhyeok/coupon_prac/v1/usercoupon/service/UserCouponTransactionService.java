package io.github.minhyeok.coupon_prac.v1.usercoupon.service;
import java.time.*;
import io.github.minhyeok.coupon_prac.v1.common.exception.*;
import io.github.minhyeok.coupon_prac.v1.campaign.repository.CampaignRepository;
import io.github.minhyeok.coupon_prac.v1.couponevent.repository.CouponEventRepository;
import io.github.minhyeok.coupon_prac.v1.couponinventory.repository.CouponInventoryRepository;
import io.github.minhyeok.coupon_prac.v1.user.repository.CouponUserRepository;
import io.github.minhyeok.coupon_prac.v1.usercoupon.dto.*;
import io.github.minhyeok.coupon_prac.v1.usercoupon.entity.UserCoupon;
import io.github.minhyeok.coupon_prac.v1.usercoupon.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static io.github.minhyeok.coupon_prac.v1.common.time.CouponTime.*;
@Service @RequiredArgsConstructor
public class UserCouponTransactionService {
    private final CouponEventRepository events;
    private final CampaignRepository campaigns;
    private final CouponUserRepository users;
    private final CouponInventoryRepository inventory;
    private final UserCouponDailyLimitRepository limits;
    private final UserCouponRepository coupons;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CouponIssueResult issue(IssueCouponCommand command) {
        var user = users.findById(command.userId());
        if (user.isEmpty() || !user.get().isActive()) throw new CouponException(CouponErrorCode.USER_NOT_ACTIVE);
        Instant now = clock.instant();
        var date = businessDate(now);
        limits.createIfAbsent(command.userId(), seconds(now)); // serializes only this user/date
        // Use a fresh read after waiting for a concurrent request from the same user.
        var existing = coupons.findByEventIdAndUserId(command.eventId(), command.userId());
        if (existing.isPresent()) return result(existing.get());
        now = clock.instant();
        if (!businessDate(now).equals(date)) throw new CouponException(CouponErrorCode.INVENTORY_BUSY);
        var event = events.findById(command.eventId())
                .orElseThrow(() -> new CouponException(CouponErrorCode.EVENT_NOT_FOUND));
        if (!event.isIssuableAt(now) || !campaigns.findById(event.getCampaignId()).orElseThrow().allowsIssuance())
            throw new CouponException(CouponErrorCode.EVENT_NOT_ISSUABLE);
        if (limits.incrementIfBelowLimit(command.userId(), date, seconds(now)) != 1)
            throw new CouponException(CouponErrorCode.DAILY_ISSUANCE_LIMIT_EXCEEDED);
        var item = inventory.lockFirstAvailableByEventId(command.eventId())
                .orElseThrow(() -> new CouponException(CouponErrorCode.INVENTORY_BUSY));
        // Native inventory lookup can also take time. Recheck the issuance deadline.
        now = clock.instant();
        if (!event.isIssuableAt(now) || !businessDate(now).equals(date))
            throw new CouponException(CouponErrorCode.EVENT_NOT_ISSUABLE);
        item.issue(now);
        return result(coupons.saveAndFlush(UserCoupon.issue(item, command.userId(), now)));
    }
    public static CouponIssueResult result(UserCoupon coupon) {
        return new CouponIssueResult(coupon.getId(), coupon.getCouponCode().toString(), coupon.getCreatedAt());
    }
}
