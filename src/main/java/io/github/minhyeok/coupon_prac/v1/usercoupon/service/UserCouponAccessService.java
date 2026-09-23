package io.github.minhyeok.coupon_prac.v1.usercoupon.service;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import io.github.minhyeok.coupon_prac.v1.campaign.entity.Campaign;
import io.github.minhyeok.coupon_prac.v1.campaign.repository.CampaignRepository;
import io.github.minhyeok.coupon_prac.v1.couponevent.repository.CouponEventRepository;
import io.github.minhyeok.coupon_prac.v1.couponusage.entity.CouponUsageHistory;
import io.github.minhyeok.coupon_prac.v1.couponusage.repository.CouponUsageHistoryRepository;
import io.github.minhyeok.coupon_prac.v1.usercoupon.entity.*;
import io.github.minhyeok.coupon_prac.v1.usercoupon.repository.UserCouponRepository;
import io.github.minhyeok.coupon_prac.v1.common.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static io.github.minhyeok.coupon_prac.v1.common.time.CouponTime.seconds;

@Service @RequiredArgsConstructor
public class UserCouponAccessService {
    private final UserCouponRepository coupons;
    private final CouponEventRepository events;
    private final CampaignRepository campaigns;
    private final CouponUsageHistoryRepository history;
    private final Clock clock;

    @Transactional
    public QrResult rotateQr(Long couponId, Long userId) {
        var coupon = coupons.lockOwned(couponId, userId)
                .orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_NOT_FOUND));
        coupon.rotateQr(clock.instant());
        return new QrResult(coupon.getQrToken(), coupon.getQrVersion(), coupon.getQrExpiresAt());
    }

    @Transactional
    public UseResult use(UUID token, Long storeId, Long ownerId, int orderAmount, Long menuId, Integer menuAmount) {
        var coupon = coupons.lockByQrToken(token)
                .orElseThrow(() -> new CouponException(CouponErrorCode.INVALID_QR));
        var event = events.findById(coupon.getEventId()).orElseThrow();
        var campaign = campaigns.findById(event.getCampaignId()).orElseThrow();
        if (!campaign.getStoreId().equals(storeId) || !campaign.getOwnerId().equals(ownerId))
            throw new CouponException(CouponErrorCode.STORE_MISMATCH);
        int amount = discount(campaign, orderAmount, menuId, menuAmount);
        Instant now = clock.instant(); // after lock wait, not request arrival time
        coupon.requireValidQr(token, now);
        byte[] binaryToken = ByteBuffer.allocate(16).putLong(token.getMostSignificantBits())
                .putLong(token.getLeastSignificantBits()).array();
        if (coupons.consume(coupon.getId(), binaryToken, seconds(now)) != 1)
            throw new CouponException(CouponErrorCode.INVALID_QR);
        var usage = history.saveAndFlush(CouponUsageHistory.used(coupon.getId(), amount, now));
        return new UseResult(coupon.getId(), usage.getId(), amount, usage.getCreatedAt());
    }

    private int discount(Campaign campaign, int orderAmount, Long menuId, Integer menuAmount) {
        if (orderAmount <= 0 || (campaign.getMinOrderAmount() != null && orderAmount < campaign.getMinOrderAmount()))
            throw new CouponException(CouponErrorCode.INVALID_ORDER);
        int base = orderAmount;
        if (campaign.getTargetMenuId() != null) {
            if (!campaign.getTargetMenuId().equals(menuId) || menuAmount == null || menuAmount <= 0 || menuAmount > orderAmount)
                throw new CouponException(CouponErrorCode.INVALID_ORDER);
            base = menuAmount;
        }
        long discount = "PERCENT".equals(campaign.getDiscountType())
                ? (long) base * campaign.getDiscountValue() / 100 : campaign.getDiscountValue();
        return (int) Math.min(base, discount);
    }

    @Transactional(readOnly = true)
    public Page<CouponView> list(Long userId, int page, int size) {
        Instant now = clock.instant();
        return coupons.findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(page, size))
                .map(c -> view(c, now));
    }
    @Transactional(readOnly = true)
    public CouponDetail detail(Long id, Long userId) {
        var coupon = coupons.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_NOT_FOUND));
        var usage = history.findByUserCouponId(id);
        return new CouponDetail(view(coupon, clock.instant()),
                usage.map(CouponUsageHistory::getDiscountAmount).orElse(null),
                usage.map(CouponUsageHistory::getCreatedAt).orElse(null));
    }
    private CouponView view(UserCoupon c, Instant now) {
        return new CouponView(c.getId(), c.getEventId(), c.getCouponCode(), c.effectiveStatus(now),
                c.getUsableStartTime(), c.getUsableEndTime(), c.getCreatedAt());
    }
    public record QrResult(UUID qrToken, int qrVersion, Instant expiresAt) {}
    public record UseResult(Long userCouponId, Long usageHistoryId, int discountAmount, Instant usedAt) {}
    public record CouponView(Long id, Long eventId, UUID couponCode, UserCouponStatus status,
                             Instant usableStartTime, Instant usableEndTime, Instant issuedAt) {}
    public record CouponDetail(CouponView coupon, Integer discountAmount, Instant usedAt) {}
}
