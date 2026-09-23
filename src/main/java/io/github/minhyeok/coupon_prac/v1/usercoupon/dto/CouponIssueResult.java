package io.github.minhyeok.coupon_prac.v1.usercoupon.dto;

import java.time.Instant;

public record CouponIssueResult(Long userCouponId, String couponCode, Instant issuedAt) {
}
