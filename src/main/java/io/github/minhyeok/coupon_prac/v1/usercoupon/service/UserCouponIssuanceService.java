package io.github.minhyeok.coupon_prac.v1.usercoupon.service;
import io.github.minhyeok.coupon_prac.v1.common.exception.*;
import io.github.minhyeok.coupon_prac.v1.couponinventory.entity.CouponInventoryStatus;
import io.github.minhyeok.coupon_prac.v1.couponinventory.repository.CouponInventoryRepository;
import io.github.minhyeok.coupon_prac.v1.usercoupon.dto.*;
import io.github.minhyeok.coupon_prac.v1.usercoupon.repository.UserCouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
@Service @RequiredArgsConstructor
public class UserCouponIssuanceService {
    private final UserCouponRepository coupons;
    private final CouponInventoryRepository inventory;
    private final UserCouponTransactionService transactionService;
    // Deliberately NOT transactional: the sold-out check must run after the failed transaction rolled back.
    public CouponIssueResult issue(IssueCouponCommand command) {
        var existing = coupons.findByEventIdAndUserId(command.eventId(), command.userId());
        if (existing.isPresent()) return UserCouponTransactionService.result(existing.get());
        try {
            return transactionService.issue(command);
        } catch (CouponException exception) {
            if (exception.getErrorCode() != CouponErrorCode.INVENTORY_BUSY) throw exception;
            // Consistent, non-locking read sees a locked-but-uncommitted row as AVAILABLE.
            if (!inventory.existsByEventIdAndStatus(command.eventId(), CouponInventoryStatus.AVAILABLE))
                throw new CouponException(CouponErrorCode.COUPON_SOLD_OUT);
            throw exception;
        }
    }
}
