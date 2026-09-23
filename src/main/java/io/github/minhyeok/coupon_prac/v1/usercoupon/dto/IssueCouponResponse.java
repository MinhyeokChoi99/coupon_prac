package io.github.minhyeok.coupon_prac.v1.usercoupon.dto;

import java.time.Instant;

public record IssueCouponResponse(Long userCouponId, String couponCode, Instant issuedAt) {

    public static IssueCouponResponse from(CouponIssueResult result) {
        return new IssueCouponResponse(result.userCouponId(), result.couponCode(), result.issuedAt());
    }
}
